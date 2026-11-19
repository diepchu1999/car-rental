package com.carrental.booking.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.port.in.CancelBookingUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.Booking;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.adapter.in.internal.ServiceCallerTestScope;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BR-301/304: gọi use case qua proxy Spring, booking/availability/SQL/PostgreSQL thật.
 * Không bọc transaction toàn test; mỗi kết quả được đọc sau khi giao dịch đã kết thúc.
 * Caller scope mô phỏng danh tính đã xác thực, không thay thế test JWT/HTTP ở bước REST.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT37M")
@Import(PostgresTestConfiguration.class)
class BookingCancellationIntegrationTest {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Instant CANCELLED_AT = CREATED.plusSeconds(60);
    @Autowired private CancelBookingUseCase cancellations;
    @Autowired private WriteBookingPort writes;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private ReadBookingPort reads;
    @MockitoSpyBean private AvailabilityDirectory availability;
    @MockitoBean(name = "applicationClock") private Clock clock;

    /** Clock dùng chung cho đơn/giữ chỗ, không để job nền can thiệp fixture trong test. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(CREATED);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** Huỷ commit cùng nhả chỗ và một lịch sử; snapshot giữ nguyên, xe và hạn mức khách đều dùng lại được. */
    @Test
    void commitsCancellationHistoryAndReleaseTogether() {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        var result = cancel(fixture.booking(), fixture.booking().customerAccountCode());
        var expected = fixture.booking().cancel(CANCELLED_AT);
        assertEquals(BookingDetail.fromBooking(expected), result);
        assertEquals(expected, reads.loadOwned(expected.code(), expected.customerAccountCode()).orElseThrow());
        assertCancelled(fixture.booking(), CANCELLED_AT);
        var replacement = availability.hold(fixture.vehicleId(), new Period(START, END),
                terms().turnaroundBuffer(), "DT-2611-REHOLD");
        assertNotEquals(fixture.booking().reservationCode(), replacement.code());
        fixture(fixture.booking().customerAccountCode());
    }

    /** Đã nhả chỗ thật rồi phát sinh lỗi: cả khoá lịch, trạng thái đơn và lịch sử phải rollback. */
    @Test
    void failureAfterActualReleaseRollsBackBothSchemas() {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        var failure = new IllegalStateException("Simulated failure after releasing the hold.");
        doAnswer(call -> { call.callRealMethod(); throw failure; })
                .when(availability).release(fixture.booking().reservationCode());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cancel(fixture.booking(), fixture.booking().customerAccountCode())));
        assertPending(fixture.booking());
        assertEquals(fixture.booking(), reads.loadOwned(fixture.booking().code(), fixture.booking().customerAccountCode()).orElseThrow());
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, assertThrows(DomainException.class,
                () -> availability.hold(fixture.vehicleId(), new Period(START, END),
                        terms().turnaroundBuffer(), "DT-2611-BLOCK1")).errorCode());
        doCallRealMethod().when(availability).release(fixture.booking().reservationCode());
        cancel(fixture.booking(), fixture.booking().customerAccountCode());
        assertCancelled(fixture.booking(), CANCELLED_AT);
    }

    /** Lỗi ghi lịch sử không được để header huỷ một mình; trigger thử cũng rollback cùng transaction. */
    @Test
    void historyFailureRollsBackCancellation() {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.getJdbcTemplate().execute(sql.load("sql/booking/install_history_failure_for_adapter_test.sql"));
                    cancel(fixture.booking(), fixture.booking().customerAccountCode());
                }));
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23514", cause.getSQLState());
        assertEquals("test_booking_history_failure", cause.getServerErrorMessage().getConstraint());
        assertPending(fixture.booking());
        verify(availability, never()).release(fixture.booking().reservationCode());
        cancel(fixture.booking(), fixture.booking().customerAccountCode());
        assertCancelled(fixture.booking(), CANCELLED_AT);
    }

    /** Use case tham gia transaction đã có, không tự commit riêng trước bên gọi. */
    @Test
    void outerFailureRollsBackSuccessfulCancellation() {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        var failure = new IllegalStateException("Simulated outer transaction failure.");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    cancel(fixture.booking(), fixture.booking().customerAccountCode());
                    throw failure;
                })));
        assertPending(fixture.booking());
    }

    /** Chủ khác và mã không tồn tại đều 404; request bị từ chối không đổi lịch sử hoặc lịch xe. */
    @Test
    void rejectsOtherOwnerAndUnknownBooking() {
        var fixture = fixture(null);
        assertDomain(ErrorCode.BOOKING_NOT_FOUND, DomainException.Category.NOT_FOUND,
                () -> cancel(fixture.booking(), "TK-OTHER1"));
        try (var scope = ServiceCallerTestScope.open(new CallerRef(fixture.booking().customerAccountCode(), Set.of("CUSTOMER"), null))) {
            assertDomain(ErrorCode.BOOKING_NOT_FOUND, DomainException.Category.NOT_FOUND,
                    () -> cancellations.cancel(new CancelBookingCommand("DT-2611-ABSENT")));
        }
        assertPending(fixture.booking());
    }

    /** Huỷ lặp sau commit không thêm sự kiện hoặc gọi nhả lần hai. */
    @Test
    void repeatedCancellationDoesNotDuplicateHistory() {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        cancel(fixture.booking(), fixture.booking().customerAccountCode());
        when(clock.instant()).thenReturn(CANCELLED_AT.plusSeconds(1));
        assertDomain(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, DomainException.Category.RULE_VIOLATION,
                () -> cancel(fixture.booking(), fixture.booking().customerAccountCode()));
        assertCancelled(fixture.booking(), CANCELLED_AT);
        verify(availability).release(fixture.booking().reservationCode());
    }

    /** Domain và SQL không tự cấm khách huỷ đơn chờ đã quá hạn nhưng job chưa xử lý (BR-103/301). */
    @Test
    void cancelsOverduePendingBookingBeforeSweep() {
        var fixture = fixture(null);
        var afterExpiry = fixture.booking().holdExpiresAt().plusSeconds(1);
        when(clock.instant()).thenReturn(afterExpiry);
        cancel(fixture.booking(), fixture.booking().customerAccountCode());
        assertCancelled(fixture.booking(), afterExpiry);
    }

    /** Cổng ghi không được tự commit khi thiếu transaction ngoài. */
    @Test
    void writePortRequiresOuterTransaction() {
        var fixture = fixture(null);
        assertThrows(IllegalTransactionStateException.class,
                () -> writes.cancelPendingByCustomer(fixture.booking().cancel(CANCELLED_AT)));
        assertPending(fixture.booking());
    }

    /** SQL kiểm chủ độc lập với use case; một aggregate giả chủ không sửa được đơn hoặc tạo lịch sử. */
    @Test
    void writePortRejectsWrongOwnerAndUncancelledInput() {
        var fixture = fixture(null);
        var original = fixture.booking();
        var cancelled = original.cancel(CANCELLED_AT);
        var forged = new Booking(cancelled.code(), "TK-OTHER1", cancelled.quoteCode(), cancelled.vehicleCode(),
                cancelled.branchCode(), cancelled.reservationCode(), cancelled.startAt(), cancelled.endAt(),
                cancelled.rentalType(), cancelled.driveMode(), cancelled.pickupMethod(), cancelled.price(),
                cancelled.rentalTerms(), cancelled.holdExpiresAt(), cancelled.status(), cancelled.createdAt(), cancelled.statusChangedAt());
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertFalse(writes.cancelPendingByCustomer(forged)));
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> writes.cancelPendingByCustomer(original)));
        assertPending(original);
    }

    /** Hai connection đã đọc cùng PENDING: đúng một thắng, một lỗi trạng thái; không lỗi SQL/deadlock lọt ra. */
    @Test
    void concurrentCancellationsHaveExactlyOneWinner() throws Exception {
        var fixture = fixture(null);
        when(clock.instant()).thenReturn(CANCELLED_AT);
        var barrier = new CyclicBarrier(2);
        Set<Integer> connections = new ConcurrentSkipListSet<>();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            connections.add(jdbc.queryForObject(sql.load("sql/booking/read_connection_for_cancellation_test.sql"), Map.of(), Integer.class));
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(reads).loadOwned(fixture.booking().code(), fixture.booking().customerAccountCode());
        var pool = Executors.newFixedThreadPool(2);
        List<Object> results;
        try {
            var first = pool.submit(() -> raceCancel(fixture.booking()));
            var second = pool.submit(() -> raceCancel(fixture.booking()));
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            doCallRealMethod().when(reads).loadOwned(fixture.booking().code(), fixture.booking().customerAccountCode());
        }
        assertEquals(2, connections.size());
        assertEquals(1, results.stream().filter(BookingDetail.class::isInstance).count(), results.toString());
        var failure = assertInstanceOf(DomainException.class,
                results.stream().filter(value -> !(value instanceof BookingDetail)).findFirst().orElseThrow());
        assertEquals(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
        assertCancelled(fixture.booking(), CANCELLED_AT);
        verify(availability).release(fixture.booking().reservationCode());
        // Phép thử đảo: tạm bỏ điều kiện status = 'PENDING_PAYMENT' trong SQL huỷ.
        // Test phải đỏ vì bên thua ghi trùng lịch sử (23505), không còn lỗi domain mong đợi; khôi phục ngay.
    }

    /** Tạo giữ chỗ thật rồi ghi fixture đơn trong cùng transaction, không cần sửa dữ liệu local. */
    private Fixture fixture(String owner) {
        long id = SEQUENCE.incrementAndGet();
        String suffix = String.format(Locale.ROOT, "CC%04d", id);
        long vehicleId = 9_400_000_000L + id;
        String account = owner == null ? "TK-" + suffix : owner;
        return new TransactionTemplate(transactions).execute(status -> {
            var reference = availability.hold(vehicleId, new Period(START, END), terms().turnaroundBuffer(), "DT-2611-" + suffix);
            var template = booking();
            var value = Booking.create("DT-2611-" + suffix, account, "BG-2611-" + suffix,
                    "XE-" + suffix, "CN-TEST01", reference.code(), START, END, template.rentalType(),
                    template.driveMode(), template.pickupMethod(), price(), terms(), reference.holdExpiresAt(), clock.instant());
            assertTrue(writes.insert(value));
            return new Fixture(value, vehicleId);
        });
    }

    /** Scope chỉ thuộc worker hiện tại; lời gọi đi qua proxy transaction thật. */
    private BookingDetail cancel(Booking booking, String owner) {
        try (var scope = ServiceCallerTestScope.open(new CallerRef(owner, Set.of("CUSTOMER"), null))) {
            return cancellations.cancel(new CancelBookingCommand(booking.code()));
        }
    }

    /** Trả lỗi nguyên gốc để assertion không che SQLSTATE hoặc lỗi hạ tầng không mong đợi. */
    private Object raceCancel(Booking booking) {
        try { return cancel(booking, booking.customerAccountCode()); }
        catch (RuntimeException failure) { return failure; }
    }

    /** Đọc SQL trên connection ngoài transaction use case sau khi commit hoặc rollback. */
    private Map<String, Object> state(Booking booking) {
        return jdbc.queryForMap(sql.load("sql/booking/read_cancellation_state_for_use_case_test.sql"), Map.of("code", booking.code()));
    }

    /** Khi rollback, giữ chỗ vẫn chặn và không có lịch sử huỷ. */
    private void assertPending(Booking booking) {
        var state = state(booking);
        assertEquals("PENDING_PAYMENT", state.get("booking_status"));
        assertEquals("HELD", state.get("reservation_status"));
        assertEquals(1L, state.get("history_count"));
        assertEquals(1L, state.get("line_count"));
        assertNull(state.get("reason"));
        assertEquals(booking.createdAt(), ((java.sql.Timestamp) state.get("status_changed_at")).toInstant());
    }

    /** Huỷ thành công phải có đúng ai/lúc nào/lý do và trạng thái đồng nhất ở cả hai schema. */
    private void assertCancelled(Booking booking, Instant at) {
        var state = state(booking);
        assertEquals("CANCELLED", state.get("booking_status"));
        assertEquals("RELEASED", state.get("reservation_status"));
        assertEquals(2L, state.get("history_count"));
        assertEquals(1L, state.get("line_count"));
        assertEquals("PENDING_PAYMENT", state.get("from_status"));
        assertEquals("CANCELLED", state.get("to_status"));
        assertEquals(booking.customerAccountCode(), state.get("actor_account_code"));
        assertEquals("CUSTOMER_CANCELLED", state.get("reason"));
        for (String name : List.of("status_changed_at", "reservation_changed_at", "occurred_at")) {
            assertEquals(at, ((java.sql.Timestamp) state.get(name)).toInstant(), name);
        }
    }

    /** Kiểm lỗi công khai đúng nhóm; không chấp nhận lỗi SQL thay cho từ chối nghiệp vụ. */
    private static void assertDomain(ErrorCode code, DomainException.Category category, Runnable action) {
        var failure = assertThrows(DomainException.class, action::run);
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }

    /** Dữ liệu riêng mỗi test, không token hoặc thông tin khách thật. */
    private record Fixture(Booking booking, long vehicleId) { }
}
