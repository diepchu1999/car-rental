package com.carrental.booking.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.availability.api.Period;
import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.command.ExpireBookingHoldCommand;
import com.carrental.booking.application.port.in.CancelBookingUseCase;
import com.carrental.booking.application.port.in.ExpireBookingHoldUseCase;
import com.carrental.booking.application.port.in.SweepExpiredBookingHoldsUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.Booking;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.adapter.in.internal.ServiceCallerTestScope;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.logging.LogEventCapture;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-103/304: SQL và transaction thật cho từng dòng; đọc sau commit độc lập, không dùng dữ liệu local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT38M")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingHoldExpiryIntegrationTest {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Instant DUE = CREATED.plusSeconds(2280);
    @Autowired private SweepExpiredBookingHoldsUseCase sweeps;
    @Autowired private ExpireBookingHoldUseCase expiry;
    @Autowired private CancelBookingUseCase cancellations;
    @Autowired private WriteBookingPort writes;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @MockitoSpyBean private ReadBookingPort reads;
    @MockitoSpyBean private AvailabilityDirectory availability;
    @MockitoBean(name = "applicationClock") private Clock clock;

    /** Tạo fixture với Clock ổn định; nhịp 24 giờ của suite tránh timer can thiệp test. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(CREATED);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** Biên đúng hạn, actor null, một lịch sử, giá không đổi; sau dọn khách và xe đều đặt lại được. */
    @Test
    void expiresAtInclusiveCutoffAndReleasesCustomerAndVehicle() {
        var f = fixture(null, null);
        when(clock.instant()).thenReturn(DUE.minusNanos(1));
        sweeps.sweep();
        assertPending(f.booking());
        when(clock.instant()).thenReturn(DUE);
        sweeps.sweep();
        assertExpired(f.booking());
        assertEquals(f.booking().expire(DUE).orElseThrow(), reads.loadOwned(f.booking().code(), f.booking().customerAccountCode()).orElseThrow());
        var replacement = availability.hold(f.vehicleId(), new Period(START, END), terms().turnaroundBuffer(), "DT-2611-REHOLD");
        assertNotEquals(f.booking().reservationCode(), replacement.code());
        fixture(f.booking().customerAccountCode(), null);
        sweeps.sweep();
        assertExpired(f.booking());
    }

    /** Đơn local có hold đã nhả vẫn được quét từ booking, đóng lại và không viết lại mốc nhả hold. */
    @Test
    void closesLegacyPendingBookingWhoseHoldWasAlreadyReleased() {
        var f = fixture(null, null);
        assertEquals(HoldExpiryResult.RELEASED, availability.expireHold(f.booking().reservationCode(), DUE));
        when(clock.instant()).thenReturn(DUE.plusSeconds(30));
        sweeps.sweep();
        var state = state(f.booking());
        assertEquals("CANCELLED", state.get("booking_status"));
        assertEquals("RELEASED", state.get("reservation_status"));
        assertEquals("HOLD_EXPIRED", state.get("reason"));
        assertEquals(DUE, ((java.sql.Timestamp) state.get("reservation_changed_at")).toInstant());
        assertEquals(2L, ((Number) state.get("history_count")).longValue());
    }

    /** Giữ khả năng dọn hold độc lập trước module booking, không sinh đơn hoặc lịch sử giả. */
    @Test
    void releasesStandaloneHoldWithoutInventingBooking() {
        var ref = availability.hold(9_800_000_001L, new Period(START, END), terms().turnaroundBuffer(), "standalone-fixture");
        when(clock.instant()).thenReturn(DUE);
        sweeps.sweep();
        assertEquals(HoldExpiryResult.ALREADY_RELEASED, availability.expireHold(ref.code(), DUE));
        assertTrue(reads.loadForExpiry("standalone-fixture").isEmpty());
    }

    /**
     * Dòng giữa lỗi sau khi SQL nhả chỗ đã chạy: rollback cả dòng, hai dòng khác vẫn commit; lượt sau thử lại được.
     * Phép thử đảo: tạm bỏ @Transactional ở BookingHoldExpiryService.expire → test phải đỏ; khôi phục ngay.
     */
    @Test
    void failureAfterReleaseRollsBackOnlyThatRowAndNextSweepRetries() {
        var first = fixture(null, null);
        var broken = fixture(null, null);
        var last = fixture(null, null);
        var failure = new IllegalStateException("Simulated failure after real release.");
        doAnswer(call -> { call.callRealMethod(); throw failure; })
                .when(availability).expireHold(broken.booking().reservationCode(), DUE);
        when(clock.instant()).thenReturn(DUE);
        try (var logs = new LogEventCapture(BookingHoldSweepService.class.getName())) {
            MDC.put("requestId", "job-fixture");
            MDC.put("api", "BookingHoldExpiryScheduler.sweep");
            try { sweeps.sweep(); } finally { MDC.clear(); }
            assertExpired(first.booking());
            assertPending(broken.booking());
            assertExpired(last.booking());
            assertTrue(logs.events().stream().anyMatch(event -> event.getFormattedMessage().startsWith("Failure summary:")
                    && event.getFormattedMessage().contains(broken.booking().code())
                    && "job-fixture".equals(event.getMDCPropertyMap().get("requestId"))
                    && "BookingHoldExpiryScheduler.sweep".equals(event.getMDCPropertyMap().get("api"))
                    && event.getThrowableProxy() != null));
        } finally {
            doCallRealMethod().when(availability).expireHold(broken.booking().reservationCode(), DUE);
        }
        sweeps.sweep();
        assertExpired(broken.booking());
        assertExpired(first.booking());
        assertExpired(last.booking());
    }

    /** Lỗi constraint lịch sử một đơn: 23514 đúng tên, không huỷ nửa vời, các đơn còn lại vẫn được dọn. */
    @Test
    void historyConstraintFailureDoesNotBlockOtherRows() {
        var first = fixture(null, null);
        var broken = fixture(null, "EXFAIL");
        var last = fixture(null, null);
        when(clock.instant()).thenReturn(DUE);
        jdbc.getJdbcTemplate().execute(sql.load("sql/booking/install_expiry_history_failure_for_test.sql"));
        try {
            var error = assertThrows(DataIntegrityViolationException.class, () -> expiry.expire(command(broken.booking())));
            var cause = assertInstanceOf(PSQLException.class, error.getMostSpecificCause());
            assertEquals("23514", cause.getSQLState());
            assertEquals("test_expiry_history_failure", cause.getServerErrorMessage().getConstraint());
            sweeps.sweep();
            assertPending(broken.booking());
            assertExpired(first.booking());
            assertExpired(last.booking());
            verify(availability, never()).expireHold(broken.booking().reservationCode(), DUE);
        } finally {
            jdbc.getJdbcTemplate().execute(sql.load("sql/booking/remove_expiry_history_failure_for_test.sql"));
        }
        sweeps.sweep();
        assertExpired(broken.booking());
    }

    /** Hold đã xác nhận không thể nhả: cập nhật đơn/lịch sử phải rollback, không báo huỷ thành công. */
    @Test
    void ineligibleHoldRollsBackBookingAndHistory() {
        var f = fixture(null, null);
        availability.confirm(f.booking().reservationCode());
        assertThrows(IllegalStateException.class, () -> expiry.expire(command(f.booking())));
        var state = state(f.booking());
        assertEquals("PENDING_PAYMENT", state.get("booking_status"));
        assertEquals("CONFIRMED", state.get("reservation_status"));
        assertEquals(1L, ((Number) state.get("history_count")).longValue());
        // Trả fixture về không chặn để không sinh lỗi lặp ở những lượt dọn khác trong lớp test.
        availability.release(f.booking().reservationCode());
        expiry.expire(command(f.booking()));
    }

    /** Một dòng REQUIRES_NEW tự commit, rollback transaction bên ngoài không kéo các dòng đã dọn về. */
    @Test
    void eachRowCommitsIndependentlyOfCallingTransaction() {
        var f = fixture(null, null);
        var failure = new IllegalStateException("Outer fixture rollback.");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    assertTrue(expiry.expire(command(f.booking())));
                    throw failure;
                })));
        assertExpired(f.booking());
    }

    /** Cổng ghi bắt buộc transaction; SQL có hạn để caller nội bộ không huỷ sớm được. */
    @Test
    void persistenceRejectsMissingTransactionAndPrematureExpiry() {
        var f = fixture(null, null);
        var expired = f.booking().expire(DUE).orElseThrow();
        assertThrows(IllegalTransactionStateException.class, () -> writes.cancelExpiredPending(expired));
        assertThrows(IllegalArgumentException.class, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(tx -> writes.cancelExpiredPending(f.booking().cancel(DUE.minusSeconds(1)))));
        assertPending(f.booking());
    }

    /** Hai kết nối đều đọc PENDING trước hàng rào: một lịch sử cuối, không deadlock/SQLSTATE lọt ra. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryRacesAnotherExpiryOrCustomerCancellation(boolean customerCancels) throws Exception {
        var f = fixture(null, null);
        when(clock.instant()).thenReturn(DUE);
        var gate = new CyclicBarrier(2);
        Set<Integer> connections = new ConcurrentSkipListSet<>();
        org.mockito.stubbing.Answer<Object> synchronize = call -> {
            Object found = call.callRealMethod();
            connections.add(jdbc.queryForObject(sql.load("sql/booking/read_connection_for_cancellation_test.sql"), Map.of(), Integer.class));
            gate.await(10, TimeUnit.SECONDS);
            return found;
        };
        doAnswer(synchronize).when(reads).loadForExpiry(f.booking().code());
        if (customerCancels) doAnswer(synchronize).when(reads).loadOwned(f.booking().code(), f.booking().customerAccountCode());
        var workers = Executors.newFixedThreadPool(2);
        List<Object> results;
        try {
            var first = workers.submit(() -> attempt(() -> expiry.expire(command(f.booking()))));
            var second = workers.submit(() -> attempt(() -> customerCancels ? cancel(f.booking()) : expiry.expire(command(f.booking()))));
            results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            doCallRealMethod().when(reads).loadForExpiry(f.booking().code());
            doCallRealMethod().when(reads).loadOwned(f.booking().code(), f.booking().customerAccountCode());
        }
        assertEquals(2, connections.size());
        assertEquals(1, results.stream().filter(value -> Boolean.TRUE.equals(value) || value instanceof BookingDetail).count());
        for (Object result : results) {
            if (result instanceof Throwable failure) {
                var domain = assertInstanceOf(DomainException.class, failure);
                assertTrue(customerCancels);
                assertEquals(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, domain.errorCode());
            }
        }
        var state = state(f.booking());
        assertEquals("CANCELLED", state.get("booking_status"));
        assertEquals("RELEASED", state.get("reservation_status"));
        assertEquals(2L, ((Number) state.get("history_count")).longValue());
        if (results.stream().anyMatch(BookingDetail.class::isInstance)) {
            assertEquals("CUSTOMER_CANCELLED", state.get("reason"));
            assertEquals(f.booking().customerAccountCode(), state.get("actor_account_code"));
        } else {
            assertEquals("HOLD_EXPIRED", state.get("reason"));
            assertNull(state.get("actor_account_code"));
        }
        var next = availability.hold(f.vehicleId(), new Period(START, END), terms().turnaroundBuffer(), "DT-2611-RACEOK");
        assertNotEquals(f.booking().reservationCode(), next.code());
    }

    /** Fixture giữ chỗ và đơn commit chung, owner/xe/mã riêng; không cần tạo tài khoản thật. */
    private Fixture fixture(String owner, String requestedSuffix) {
        long id = SEQUENCE.incrementAndGet();
        String suffix = requestedSuffix == null ? String.format(Locale.ROOT, "EX%04d", id) : requestedSuffix;
        long vehicleId = 9_900_000_000L + id;
        var initialClock = clock.instant();
        when(clock.instant()).thenReturn(CREATED);
        try {
            return new TransactionTemplate(transactions).execute(tx -> {
                var hold = availability.hold(vehicleId, new Period(START, END), terms().turnaroundBuffer(), "DT-2611-" + suffix);
                var template = booking();
                var value = Booking.create("DT-2611-" + suffix, owner == null ? "TK-" + suffix : owner,
                        "BG-2611-" + suffix, "XE-" + suffix, "CN-TEST01", hold.code(), START, END,
                        template.rentalType(), template.driveMode(), template.pickupMethod(), price(), terms(), hold.holdExpiresAt(), CREATED);
                assertTrue(writes.insert(value));
                return new Fixture(value, vehicleId);
            });
        } finally { when(clock.instant()).thenReturn(initialClock); }
    }

    /** Mốc đã đóng băng 38 phút khác mặc định một giờ. */
    private static ExpireBookingHoldCommand command(Booking value) {
        return new ExpireBookingHoldCommand(value.code(), value.reservationCode(), DUE);
    }

    /** Caller scope chỉ dành cho nhánh HTTP mô phỏng khách, job không dùng scope này. */
    private BookingDetail cancel(Booking value) {
        try (var scope = ServiceCallerTestScope.open(new CallerRef(value.customerAccountCode(), Set.of("CUSTOMER"), null))) {
            return cancellations.cancel(new CancelBookingCommand(value.code()));
        }
    }

    /** Không che exception hạ tầng thành kết quả nghiệp vụ trong worker. */
    private static Object attempt(java.util.concurrent.Callable<Object> call) {
        try { return call.call(); } catch (Exception failure) { return failure; }
    }

    /** Truy vấn kiểm chứng độc lập, sau khi proxy đã commit hoặc rollback. */
    private Map<String, Object> state(Booking value) {
        return jdbc.queryForMap(sql.load("sql/booking/read_cancellation_state_for_use_case_test.sql"), Map.of("code", value.code()));
    }

    /** Không còn tác dụng một phần sau lỗi. */
    private void assertPending(Booking value) {
        var state = state(value);
        assertEquals("PENDING_PAYMENT", state.get("booking_status"));
        assertEquals("HELD", state.get("reservation_status"));
        assertEquals(1L, ((Number) state.get("history_count")).longValue());
        assertNull(state.get("reason"));
        assertEquals(value, reads.loadForExpiry(value.code()).orElseThrow());
    }

    /** Một sự kiện hệ thống, đúng mốc, không sửa snapshot hoặc thêm dòng giá. */
    private void assertExpired(Booking value) {
        var state = state(value);
        assertEquals("CANCELLED", state.get("booking_status"));
        assertEquals("RELEASED", state.get("reservation_status"));
        assertEquals("HOLD_EXPIRED", state.get("reason"));
        assertNull(state.get("actor_account_code"));
        assertEquals(2L, ((Number) state.get("history_count")).longValue());
        assertEquals(value.price().lines().size(), ((Number) state.get("line_count")).intValue());
        assertEquals(DUE, ((java.sql.Timestamp) state.get("status_changed_at")).toInstant());
        assertEquals(DUE, ((java.sql.Timestamp) state.get("reservation_changed_at")).toInstant());
        assertEquals(value.expire(DUE).orElseThrow(), reads.loadForExpiry(value.code()).orElseThrow());
    }

    /** Mã xe nội bộ chỉ dùng chuẩn bị giữ chỗ thật. */
    private record Fixture(Booking booking, long vehicleId) { }
}
