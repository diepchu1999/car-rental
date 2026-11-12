package com.carrental.booking.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.booking.application.command.CreateBookingCommand;
import com.carrental.booking.application.port.in.CreateBookingUseCase;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.*;
import com.carrental.identity.api.*;
import com.carrental.identity.adapter.in.internal.ServiceCallerTestScope;
import com.carrental.pricing.api.QuoteDirectory;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.*;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static com.carrental.booking.application.service.BookingCreationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Gọi use case qua proxy Spring, PostgreSQL thật và hai connection thật; không có transaction bọc test.
 * Chỉ stub hợp đồng báo giá/xe/giấy tờ; rental, availability, booking và savepoint đều chạy code thật.
 * Không chứng minh JWT/HTTP ở đây; ServiceCallerTestScope mô phỏng danh tính đã xác thực.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT37M")
@Import(PostgresTestConfiguration.class)
class BookingCreationIntegrationTest {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    @Autowired private CreateBookingUseCase creates;
    @Autowired private AvailabilityDirectory availability;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean(name = "applicationClock") private Clock clock;
    @MockitoBean private QuoteDirectory quotes;
    @MockitoBean private VehicleDirectory vehicles;
    @MockitoBean private RentalEligibilityDirectory eligibility;
    @MockitoSpyBean private WriteBookingPort writes;
    private WriteBookingPort writesTarget;
    @MockitoSpyBean private ReadBookingPort reads;
    @MockitoSpyBean private BusinessCodeGenerator codes;

    /** Mốc trước chuyến ba ngày, không bị giới hạn báo trước; scheduler không thấy hold quá hạn. */
    @BeforeEach
    void setUp() {
        // Chỉ đặt lỗi giả trên spy bên trong; use case vẫn gọi bean qua proxy transaction thật.
        writesTarget = AopTestUtils.getUltimateTargetObject(writes);
        assertTrue(mockingDetails(writesTarget).isSpy());
        when(clock.instant()).thenReturn(CREATED);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** Giá gồm bảo hiểm, hạn 37 phút và đệm hai giờ đều được giữ đúng trong hai schema. */
    @Test
    void commitsBookingSnapshotAndActualHoldTogether() {
        var input = input();
        var result = create(input);
        assertEquals(price(), result.price());
        assertEquals(Duration.ofHours(2), result.rentalTerms().turnaroundBuffer());
        assertEquals(Optional.empty(), result.rentalTerms().minimumDuration());
        assertEquals(LocalTime.of(6, 0), result.rentalTerms().branchOpensAt());
        assertEquals(LocalTime.of(23, 0), result.rentalTerms().branchClosesAt());
        assertEquals(Duration.ofHours(1), result.rentalTerms().minimumAdvance());
        assertEquals(java.time.Period.ofMonths(6), result.rentalTerms().maximumAdvance());
        assertEquals(BookingStatus.PENDING_PAYMENT, result.status());
        assertEquals(CREATED.plusSeconds(2220), result.holdExpiresAt());
        assertEquals(BookingDetail.fromBooking(reads.loadOwned(result.code(), input.owner()).orElseThrow()), result);
        assertCounts(input, 1, 1);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                sql.load("sql/booking/read_creation_hold_for_use_case_test.sql"), Map.of("vehicleId", input.vehicleId()),
                (rs, row) -> {
                    assertEquals(result.reservationCode(), rs.getString("code"));
                    assertEquals(result.code(), rs.getString("booking_code"));
                    assertEquals(result.holdExpiresAt(), rs.getObject("hold_expires_at", OffsetDateTime.class).toInstant());
                    assertEquals(START, rs.getObject("starts_at", OffsetDateTime.class).toInstant());
                    assertEquals(END.plusSeconds(7200), rs.getObject("ends_at", OffsetDateTime.class).toInstant());
                    return true;
                })));
        verify(eligibility).requireSelfDriveEligible(input.owner(), 4, "AUTOMATIC", END);
    }

    /** Phát lại sau huỷ vẫn là response tạo đầu tiên; không gọi lại pricing hoặc cấp hold mới. */
    @Test
    void replaysCreationAfterStoredStatusChanges() {
        var input = input();
        var first = create(input);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            availability.release(first.reservationCode());
            assertEquals(1, jdbc.update(sql.load("sql/booking/cancel_booking_for_replay_test.sql"), Map.of(
                    "code", first.code(), "changedAt", OffsetDateTime.ofInstant(CREATED.plusSeconds(60), ZoneOffset.UTC))));
        });
        clearInvocations(quotes, vehicles, eligibility);
        assertEquals(first, create(input));
        assertEquals(BookingStatus.CANCELLED, reads.loadOwned(first.code(), input.owner()).orElseThrow().status());
        verifyNoInteractions(quotes, vehicles, eligibility);
        var counts = counts(input);
        assertEquals(1L, counts.get("reservations"));
        assertEquals(0L, counts.get("held"));
        assertEquals(1L, counts.get("keys"));
    }

    /** Cùng chuỗi key của hai khách vẫn là hai phạm vi độc lập (api-guideline §5). */
    @Test
    void sameKeyBelongsToEachCallerSeparately() {
        var first = input();
        var fresh = input();
        var second = new Input(fresh.owner(), fresh.quoteCode(), fresh.vehicleCode(), fresh.vehicleId(), first.key());
        var a = create(first);
        var b = create(second);
        assertNotEquals(a.code(), b.code());
        assertEquals(first.owner(), a.customerAccountCode());
        assertEquals(second.owner(), b.customerAccountCode());
        assertCounts(first, 1, 1);
        assertCounts(second, 1, 1);
    }

    /** Đúng biên 24 giờ phải tạo kết quả mới, không phát lại đơn cũ đã huỷ. */
    @Test
    void expiredKeyCreatesNewBookingAtTwentyFourHourBoundary() {
        var input = input();
        var first = create(input);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            availability.release(first.reservationCode());
            assertEquals(1, jdbc.update(sql.load("sql/booking/cancel_booking_for_replay_test.sql"), Map.of(
                    "code", first.code(), "changedAt", OffsetDateTime.ofInstant(CREATED.plusSeconds(60), ZoneOffset.UTC))));
        });
        var later = CREATED.plus(Duration.ofHours(24));
        when(clock.instant()).thenReturn(later);
        var renewed = new Input(input.owner(), "BG-2611-RENEW1", input.vehicleCode(), input.vehicleId(), input.key());
        var freshQuote = quote("RENEW1", input.owner(), input.vehicleCode());
        when(quotes.getForBooking(renewed.quoteCode(), renewed.owner(), later)).thenReturn(
                new com.carrental.pricing.api.QuoteRef(freshQuote.code(), freshQuote.customerAccountCode(),
                        freshQuote.vehicleCode(), freshQuote.startAt(), freshQuote.endAt(), freshQuote.rentalType(),
                        freshQuote.driveMode(), freshQuote.pickupMethod(), freshQuote.dailyRate(), freshQuote.lines(),
                        freshQuote.insuranceCalculation(), freshQuote.insuranceValue(), freshQuote.insuranceBillingDays(),
                        freshQuote.rentalAmount(), freshQuote.insuranceFee(), freshQuote.totalAmount(), later, later.plusSeconds(900)));
        var second = create(renewed);
        assertNotEquals(first.code(), second.code());
        assertEquals(renewed.quoteCode(), second.quoteCode());
        assertEquals(later, second.createdAt());
        assertEquals(second, create(renewed));
        var counts = counts(input);
        for (String name : List.of("headers", "lines", "history", "reservations")) assertEquals(2L, counts.get(name), name);
        assertEquals(1L, counts.get("keys"));
        assertEquals(1L, counts.get("held"));
    }

    /** Key còn hiệu lực khác payload bị 409, không ghi thêm bất kỳ chỗ giữ nào. */
    @Test
    void rejectsChangedQuoteForSameKey() {
        var input = input();
        create(input);
        var changed = new Input(input.owner(), "BG-2611-CHANGE", input.vehicleCode(), input.vehicleId(), input.key());
        assertDomain(ErrorCode.IDEMPOTENCY_KEY_REUSED, () -> create(changed));
        assertCounts(input, 1, 1);
    }

    /** Khi giấy tờ bị từ chối, claim cũng rollback nên sửa giấy tờ xong dùng lại cùng key được. */
    @Test
    void eligibilityFailureDoesNotPoisonKey() {
        var input = input();
        doThrow(DomainException.forbidden(ErrorCode.DRIVER_LICENSE_NOT_VERIFIED)).when(eligibility)
                .requireSelfDriveEligible(input.owner(), 4, "AUTOMATIC", END);
        assertDomain(ErrorCode.DRIVER_LICENSE_NOT_VERIFIED, () -> create(input));
        assertCounts(input, 0, 0);
        doNothing().when(eligibility).requireSelfDriveEligible(input.owner(), 4, "AUTOMATIC", END);
        create(input);
        assertCounts(input, 1, 1);
    }

    /** Đã INSERT header, dòng giá, lịch sử rồi lỗi thì cả hold và key vẫn phải rollback. */
    @Test
    void failureAfterWritingSnapshotRollsBackAllRows() {
        var input = input();
        var failure = new IllegalStateException("Simulated failure after writing the booking.");
        doAnswer(call -> {
            call.callRealMethod();
            throw failure;
        }).when(writesTarget).insert(any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> create(input)));
        assertCounts(input, 0, 0);
    }

    /** Lỗi reload cuối use case không được trả thành công hoặc để dữ liệu commit một nửa. */
    @Test
    void missingReloadRollsBackAllRows() {
        var input = input();
        doReturn(Optional.empty()).when(reads).loadOwned(any(), eq(input.owner()));
        assertThrows(IllegalStateException.class, () -> create(input));
        assertCounts(input, 0, 0);
    }

    /** Trùng mã thật sau giữ chỗ: savepoint xoá hold lần đầu, claim trỏ mã mới và chỉ một đơn commit. */
    @Test
    void codeCollisionRollsBackHoldBeforeRetry() {
        var input = input();
        var existing = Booking.create("DT-2611-OLD001", "TK-OLD001", "BG-2611-OLD001", "XE-OLD001", "CN-OLD001",
                "KL-OLD001", START, END, booking().rentalType(), booking().driveMode(), booking().pickupMethod(),
                price(), terms(), CREATED.plusSeconds(2220), CREATED);
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertTrue(writes.insert(existing)));
        doReturn("DT-OLD001", "DT-NEW001").when(codes).generate("DT");
        var result = create(input);
        assertEquals("DT-2611-NEW001", result.code());
        assertCounts(input, 1, 1);
        assertEquals(result, create(input));
        assertEquals(existing, reads.loadOwned(existing.code(), existing.customerAccountCode()).orElseThrow());
        verify(codes, times(2)).generate("KL");
    }

    /** Sau năm lần trùng mã, không có hold mồ côi hoặc key tham chiếu kết quả chưa từng tạo. */
    @Test
    void exhaustedCodeRetriesRollBackAllRows() {
        var input = input();
        doReturn(false).when(writesTarget).insert(any());
        assertThrows(IllegalStateException.class, () -> create(input));
        verify(writesTarget, times(5)).insert(any());
        verify(codes, times(5)).generate("KL");
        assertCounts(input, 0, 0);
    }

    /** Hai khách cùng xe: một commit, bên thua VEHICLE_NOT_AVAILABLE; không lỗi deadlock lọt ra. */
    @Test
    void twoCustomersCannotBookTheSameVehicle() throws Exception {
        var first = input();
        var second = inputForVehicle(first.vehicleCode(), first.vehicleId());
        synchronizeEligibility();
        var results = race(() -> create(first), () -> create(second));
        assertRace(results, ErrorCode.VEHICLE_NOT_AVAILABLE);
        var winner = results.getFirst() instanceof BookingDetail ? first : second;
        var loser = winner == first ? second : first;
        assertCounts(winner, 1, 1);
        assertCounts(loser, 0, 1);
    }

    /** Cùng khách, hai xe khác nhau và hai key: unique BR-131 giữ đúng một đơn và rollback hold thua. */
    @Test
    void oneCustomerCannotCreateTwoPendingBookings() throws Exception {
        var first = input();
        var other = input();
        var second = new Input(first.owner(), other.quoteCode(), other.vehicleCode(), other.vehicleId(), other.key());
        stub(second);
        synchronizeEligibility();
        var results = race(() -> create(first), () -> create(second));
        assertRace(results, ErrorCode.BOOKING_PENDING_PAYMENT_EXISTS);
        var winner = results.getFirst() instanceof BookingDetail ? first : second;
        var loser = winner == first ? second : first;
        assertCounts(winner, 1, 1);
        assertCounts(loser, 1, 0);
    }

    /** Hai request cùng key chờ transaction thắng rồi trả cùng kết quả; chỉ một hold và một lịch sử. */
    @Test
    void concurrentSameKeyReturnsOneCreationResult() throws Exception {
        var input = input();
        var results = race(() -> create(input), () -> create(input));
        var first = assertInstanceOf(BookingDetail.class, results.getFirst());
        assertEquals(first, assertInstanceOf(BookingDetail.class, results.getLast()));
        assertCounts(input, 1, 1);
        verify(quotes).getForBooking(eq(input.quoteCode()), eq(input.owner()), any());
        verify(codes).generate("KL");
    }

    /** Mã riêng mỗi test, không dọn dữ liệu bất biến hoặc dùng tài khoản/xe local. */
    private Input input() {
        long id = SEQUENCE.incrementAndGet();
        String suffix = String.format(Locale.ROOT, "CR%04d", id);
        var result = new Input("TK-" + suffix, "BG-2611-" + suffix, "XE-" + suffix,
                9_100_000_000L + id, UUID.randomUUID().toString());
        stub(result);
        return result;
    }

    /** Khách khác nhưng cùng tài nguyên vật lý cho cuộc đua chống chồng lịch. */
    private Input inputForVehicle(String vehicleCode, long vehicleId) {
        var fresh = input();
        var result = new Input(fresh.owner(), fresh.quoteCode(), vehicleCode, vehicleId, fresh.key());
        stub(result);
        return result;
    }

    /** Stub đúng biên đã được kiểm độc lập; không thay availability hoặc persistence của booking. */
    private void stub(Input input) {
        when(quotes.getForBooking(eq(input.quoteCode()), eq(input.owner()), any()))
                .thenReturn(quote(input.quoteCode().substring(8), input.owner(), input.vehicleCode()));
        when(vehicles.findActiveByCode(input.vehicleCode()))
                .thenReturn(Optional.of(new VehicleRef(input.vehicleId(), input.vehicleCode(), "CN-TEST01", 4, "AUTOMATIC")));
    }

    /** Caller chỉ tồn tại trong thread worker và luôn được thu hồi kể cả khi giao dịch thất bại. */
    private BookingDetail create(Input input) {
        try (var scope = ServiceCallerTestScope.open(new CallerRef(input.owner(), Set.of("CUSTOMER"), null))) {
            return creates.create(new CreateBookingCommand(input.quoteCode(), input.key()));
        }
    }

    /** Ép cả hai transaction đã chiếm key và tới cùng điểm trước INSERT, không dùng sleep. */
    private void synchronizeEligibility() {
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(10, TimeUnit.SECONDS); return null; }).when(eligibility)
                .requireSelfDriveEligible(any(), anyInt(), any(), any());
    }

    /** Bắt đầu đồng thời; lỗi không mong đợi được trả về để assertion báo đúng nguyên nhân. */
    private List<Object> race(Supplier<BookingDetail> first, Supplier<BookingDetail> second) throws Exception {
        var start = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> runRacer(start, first));
            var b = pool.submit(() -> runRacer(start, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** Mỗi worker mở transaction qua use case; không dùng connection chung giữa hai luồng. */
    private Object runRacer(CyclicBarrier start, Supplier<BookingDetail> action) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try { return action.get(); } catch (RuntimeException failure) { return failure; }
    }

    /** Không chấp nhận 500/deadlock thay cho lỗi xung đột đã định nghĩa. */
    private void assertRace(List<Object> results, ErrorCode expected) {
        assertEquals(1, results.stream().filter(BookingDetail.class::isInstance).count(), results.toString());
        var failure = assertInstanceOf(DomainException.class,
                results.stream().filter(value -> !(value instanceof BookingDetail)).findFirst().orElseThrow());
        assertEquals(expected, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }

    /** Kiểm mã lỗi công khai, không chỉ kiểm có exception. */
    private void assertDomain(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(DomainException.class, action::run).errorCode());
    }

    /** Đọc không qua spy sau khi outer transaction đã kết thúc. */
    private Map<String, Object> counts(Input input) {
        return jdbc.queryForMap(sql.load("sql/booking/read_creation_counts_for_use_case_test.sql"),
                Map.of("owner", input.owner(), "vehicleId", input.vehicleId()));
    }

    /** Mỗi đơn thắng có đúng một dòng giá, lịch sử và key; đếm cả hold lỗi để bắt orphan. */
    private void assertCounts(Input input, long bookings, long holds) {
        var counts = counts(input);
        for (String name : List.of("headers", "lines", "history", "keys")) assertEquals(bookings, counts.get(name), name);
        assertEquals(holds, counts.get("reservations"), "reservations");
        assertEquals(holds, counts.get("held"), "held");
    }

    /** Dữ liệu caller/request độc lập từng luồng, không mang token hay số điện thoại. */
    private record Input(String owner, String quoteCode, String vehicleCode, long vehicleId, String key) { }
}
