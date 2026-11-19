package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.ExpiredHoldRef;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-103/304: API nội bộ → use case → adapter → PostgreSQL thật, không bọc transaction toàn test.
 * Context riêng với TTL khác hạn fixture; không thể xanh do tính lại TTL từ cấu hình.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT79M")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationSingleHoldExpiryIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(1800);
    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(NOW.plusSeconds(86400), NOW.plusSeconds(93600));
    private static final AtomicLong SEQUENCE = new AtomicLong();
    @Autowired private AvailabilityDirectory availability;
    @Autowired private WriteReservationPort writes;
    @Autowired private ReadReservationPort reads;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlLoader sql;

    /** Danh sách chỉ gồm HELD đến hạn, biên bao gồm, thứ tự theo hạn rồi mã; đọc không sửa bản ghi. */
    @Test
    void listsOnlyDueHeldInStableOrderWithoutMutation() {
        var at = seed(ReservationStatus.HELD, NOW);
        var before = seed(ReservationStatus.HELD, NOW.minusNanos(1000));
        var tied = seed(ReservationStatus.HELD, NOW);
        var after = seed(ReservationStatus.HELD, NOW.plusNanos(1000));
        var confirmed = seed(ReservationStatus.CONFIRMED, NOW.minusSeconds(1));
        var released = seed(ReservationStatus.RELEASED, NOW.minusSeconds(1));
        var fixtures = List.of(at, before, tied, after, confirmed, released);
        var codes = fixtures.stream().map(Reservation::code).toList();
        var actual = availability.findExpiredHolds(NOW).stream().filter(ref -> codes.contains(ref.reservationCode())).toList();
        var expected = List.of(at, before, tied).stream()
                .sorted(Comparator.comparing(Reservation::holdExpiresAt).thenComparing(Reservation::code))
                .map(item -> new ExpiredHoldRef(item.code(), item.bookingCode(), item.holdExpiresAt())).toList();
        assertEquals(expected, actual);
        for (var original : fixtures) assertStored(original, original.status(), CREATED);
    }

    /** Tới đúng hạn nhả duy nhất mã được chọn, giữ nguyên dữ liệu; gọi lặp không ghi lại mốc. */
    @Test
    void releasesOnlyChosenHoldAndDoesNotRewriteOnRepeat() {
        var original = seed(ReservationStatus.HELD, NOW);
        var other = seed(ReservationStatus.HELD, NOW);
        assertEquals(Set.of(original.vehicleId()), reads.findBusyVehicleIds(PERIOD, List.of(original.vehicleId())));
        assertEquals(HoldExpiryResult.RELEASED, availability.expireHold(original.code(), NOW));
        assertStored(original, ReservationStatus.RELEASED, NOW);
        assertStored(other, ReservationStatus.HELD, CREATED);
        assertTrue(reads.findBusyVehicleIds(PERIOD, List.of(original.vehicleId())).isEmpty());
        assertEquals(HoldExpiryResult.ALREADY_RELEASED, availability.expireHold(original.code(), NOW.plusSeconds(30)));
        assertStored(original, ReservationStatus.RELEASED, NOW);
    }

    /** Thiếu một nano giây vẫn chưa hết hạn; JDBC không được làm tròn lên, cả đọc và ghi cùng biên. */
    @Test
    void doesNotRoundUpBeforeDeadline() {
        var original = seed(ReservationStatus.HELD, NOW);
        assertTrue(availability.findExpiredHolds(NOW.minusNanos(1)).stream()
                .noneMatch(ref -> ref.reservationCode().equals(original.code())));
        assertEquals(HoldExpiryResult.NOT_ELIGIBLE, availability.expireHold(original.code(), NOW.minusNanos(1)));
        assertStored(original, ReservationStatus.HELD, CREATED);
        assertEquals(Set.of(original.vehicleId()), reads.findBusyVehicleIds(PERIOD, List.of(original.vehicleId())));
        assertEquals(HoldExpiryResult.RELEASED, availability.expireHold(original.code(), NOW));
    }

    /** Chặn phá hoại: job không được nhả CONFIRMED/IN_USE/COMPLETED/BLOCKED, không ghi lại RELEASED. */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "HELD", mode = EnumSource.Mode.EXCLUDE)
    void preservesAllNonHeldStatuses(ReservationStatus status) {
        var original = seed(status, NOW);
        assertEquals(status == ReservationStatus.RELEASED ? HoldExpiryResult.ALREADY_RELEASED : HoldExpiryResult.NOT_ELIGIBLE,
                availability.expireHold(original.code(), NOW));
        assertStored(original, status, CREATED);
    }

    /** Bản RELEASED trước hạn không được coi là hết hạn ở mốc quá sớm. */
    @Test
    void earlyReleasedHoldIsNotYetExpired() {
        var original = seed(ReservationStatus.RELEASED, NOW.plusSeconds(1));
        assertEquals(HoldExpiryResult.NOT_ELIGIBLE, availability.expireHold(original.code(), NOW));
        assertStored(original, ReservationStatus.RELEASED, CREATED);
    }

    /** Mã không tồn tại phải báo RESERVATION_NOT_FOUND, không tạo thành công giả để huỷ đơn. */
    @Test
    void reportsMissingHold() {
        var failure = assertThrows(DomainException.class, () -> availability.expireHold("KL-ABSENT", NOW));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** Danh sách bị cũ sau khi xác nhận: câu ghi vẫn kiểm trạng thái thực tế và giữ chỗ đã xác nhận. */
    @Test
    void staleCandidateCannotReleaseConfirmedHold() {
        var original = seed(ReservationStatus.HELD, NOW);
        assertTrue(availability.findExpiredHolds(NOW).stream().anyMatch(ref -> ref.reservationCode().equals(original.code())));
        // Mô phỏng transaction xác nhận đã thắng trước câu ghi của job; chỉ sửa fixture trong test.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertTrue(writes.updateStatus(
                original.code(), ReservationStatus.HELD, ReservationStatus.CONFIRMED, NOW.minusSeconds(1))));
        assertEquals(HoldExpiryResult.NOT_ELIGIBLE, availability.expireHold(original.code(), NOW));
        assertStored(original, ReservationStatus.CONFIRMED, NOW.minusSeconds(1));
    }

    /** Savepoint không tự commit: lỗi sau nhả rollback cùng transaction ngoài, lượt sau làm lại được. */
    @Test
    void outerFailureRollsBackReleaseAndAllowsRetry() {
        var original = seed(ReservationStatus.HELD, NOW);
        var failure = new IllegalStateException("Simulated outer transaction failure.");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    assertEquals(HoldExpiryResult.RELEASED, availability.expireHold(original.code(), NOW));
                    throw failure;
                })));
        assertStored(original, ReservationStatus.HELD, CREATED);
        assertEquals(HoldExpiryResult.RELEASED, availability.expireHold(original.code(), NOW));
        assertStored(original, ReservationStatus.RELEASED, NOW);
    }

    /**
     * Hai kết nối đều thấy HELD trước hàng rào; đúng một RELEASED, một ALREADY_RELEASED, không lỗi SQL lọt ra.
     * Phép thử đảo: tạm bỏ AND status = 'HELD' trong release_expired_hold.sql → hai RELEASED → test đỏ.
     * Khôi phục SQL và đọc lại file ngay sau phép thử; không có cờ phục vụ test trong production.
     */
    @Test
    void concurrentExpiryHasExactlyOneWinner() throws Exception {
        var original = seed(ReservationStatus.HELD, NOW);
        var gate = new CyclicBarrier(2);
        var connections = new ConcurrentSkipListSet<Integer>();
        var connectionSql = sql.load("sql/availability/read_connection_for_single_expiry_test.sql");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() ->
                    new TransactionTemplate(transactions).execute(tx -> {
                        connections.add(jdbc.queryForObject(connectionSql, Integer.class));
                        assertEquals(ReservationStatus.HELD, reads.loadAggregate(original.code()).orElseThrow().status());
                        try { gate.await(10, TimeUnit.SECONDS); }
                        catch (Exception exception) { throw new IllegalStateException("Could not synchronize expiry workers.", exception); }
                        return availability.expireHold(original.code(), NOW);
                    }))).toList();
            var results = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(2, connections.size());
            assertEquals(1, results.stream().filter(result -> result == HoldExpiryResult.RELEASED).count());
            assertEquals(1, results.stream().filter(result -> result == HoldExpiryResult.ALREADY_RELEASED).count());
        }
        assertStored(original, ReservationStatus.RELEASED, NOW);
        assertTrue(reads.findBusyVehicleIds(PERIOD, List.of(original.vehicleId())).isEmpty());
    }

    /** Mỗi fixture commit trước khi gọi API; mã/xe riêng, không đụng dữ liệu local. */
    private Reservation seed(ReservationStatus status, Instant deadline) {
        long sequence = SEQUENCE.incrementAndGet();
        String suffix = String.format(Locale.ROOT, "%06d", sequence);
        boolean operational = status == ReservationStatus.BLOCKED;
        var original = Reservation.restore("KL-" + suffix, 9_100_000_000_000L + sequence, PERIOD,
                operational ? ReservationKind.MAINTENANCE : ReservationKind.RENTAL, status,
                operational ? null : "DT-3010-" + suffix, "Single expiry fixture",
                operational ? null : deadline, CREATED, CREATED);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertTrue(writes.insert(original).isPresent()));
        return original;
    }

    /** Đọc sau commit/rollback bằng kết nối độc lập với transaction vừa xử lý và đối chiếu toàn bộ dữ liệu. */
    private void assertStored(Reservation original, ReservationStatus status, Instant changedAt) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var stored = reads.loadAggregate(original.code()).orElseThrow();
        assertEquals(status, stored.status());
        assertEquals(changedAt, stored.statusChangedAt());
        assertEquals(original.code(), stored.code());
        assertEquals(original.vehicleId(), stored.vehicleId());
        assertEquals(original.period(), stored.period());
        assertEquals(original.kind(), stored.kind());
        assertEquals(original.bookingCode(), stored.bookingCode());
        assertEquals(original.reason(), stored.reason());
        assertEquals(original.holdExpiresAt(), stored.holdExpiresAt());
        assertEquals(original.createdAt(), stored.createdAt());
    }
}
