package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

/**
 * Kiểm BR-015 xuyên API nội bộ, use case, domain, CAS và PostgreSQL thật.
 *
 * <p>Không có transaction bao quanh test; kết quả worker chỉ được trả sau commit/rollback.
 * Spy cổng đọc chỉ đặt chốt sau SELECT thật trong cuộc đua hai lần dời; không giả dữ liệu hoặc kết quả SQL.
 * Mỗi ca dùng xe riêng, dữ liệu chỉ thuộc container test được đóng cuối lớp.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationComplianceMoveIntegrationTest {
    private static final Instant START = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant NEXT = START.plus(Duration.ofDays(2));
    private static final AtomicLong VEHICLES = new AtomicLong(9_000_000_800_000L);

    @Autowired
    private AvailabilityDirectory directory;
    @Autowired
    private WriteReservationPort writes;
    @MockitoSpyBean
    private ReadReservationPort reads;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SqlLoader loader;
    private String readSql;
    private String prepareSql;
    private String overlapSql;
    private String readVehicleSql;

    /** Tải SQL đọc độc lập và xác nhận test không bị bọc trong transaction ngầm. */
    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        readSql = loader.load("sql/availability/read_reservation_for_constraint_test.sql");
        prepareSql = loader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        overlapSql = loader.load("sql/availability/count_overlapping_blocking_reservations_for_test.sql");
        readVehicleSql = loader.load("sql/availability/read_committed_holds_for_test.sql");
    }

    /** Kiểm giữ nguyên ID/mã/các trường, khoảng vừa mở giữ được và sau mốc mới vẫn bị chặn. */
    @Test
    void opensOnlyReleasedIntervalAndPreservesStoredFields() {
        long vehicleId = VEHICLES.incrementAndGet();
        ReservationRef ref = compliance(vehicleId);
        Map<String, Object> before = row(ref.code());
        assertVehicleBusy(() -> directory.hold(vehicleId, new Period(START, NEXT), Duration.ZERO, "before-renewal"));

        directory.moveComplianceHoldStart(ref.code(), NEXT);

        assertOnlyStartChanged(before, NEXT);
        ReservationRef held = directory.hold(vehicleId, new Period(START, NEXT), Duration.ZERO, "after-renewal");
        assertNotNull(held);
        assertVehicleBusy(() -> directory.hold(vehicleId, new Period(NEXT, NEXT.plusSeconds(3600)),
                Duration.ZERO, "still-blocked"));
        assertVehicleBusy(() -> directory.hold(vehicleId,
                new Period(NEXT.plusSeconds(86400), NEXT.plusSeconds(90000)),
                Duration.ZERO, "blocked-after-new-start"));
        assertNoOverlaps(vehicleId);
    }

    /** Kiểm mốc bằng/lùi bị từ chối qua API thật, đọc lại cho thấy bản ghi không đổi. */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonIncreasingStartWithoutChangingRow(long seconds) {
        ReservationRef ref = compliance(VEHICLES.incrementAndGet());
        Map<String, Object> before = row(ref.code());
        DomainException failure = assertThrows(DomainException.class,
                () -> directory.moveComplianceHoldStart(ref.code(), START.plusSeconds(seconds)));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(before, row(ref.code()));
    }

    /** Kiểm loại khóa khác bị domain từ chối và UPDATE trực tiếp qua port cũng không khớp điều kiện kind. */
    @Test
    void rejectsOtherKindAtUseCaseAndWritePort() {
        ReservationRef ref = directory.block(VEHICLES.incrementAndGet(), new Period(START, NEXT),
                BlockKind.MAINTENANCE, "Workshop");
        Map<String, Object> before = row(ref.code());
        DomainException failure = assertThrows(DomainException.class,
                () -> directory.moveComplianceHoldStart(ref.code(), NEXT));
        assertEquals(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertFalse(writes.moveComplianceHoldStart(ref.code(), START, NEXT)));
        assertEquals(before, row(ref.code()));
    }

    /** Kiểm không có mã trả NOT_FOUND, không tạo bản ghi thay thế. */
    @Test
    void reportsMissingReservation() {
        DomainException failure = assertThrows(DomainException.class,
                () -> directory.moveComplianceHoldStart("KL-NONE00", NEXT));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** Kiểm port từ chối mốc cũ đã lỗi thời và mã không tồn tại, không đổi bản thắng trước đó. */
    @Test
    void staleStartAndMissingCodeDoNotOverwrite() {
        ReservationRef ref = compliance(VEHICLES.incrementAndGet());
        directory.moveComplianceHoldStart(ref.code(), NEXT);
        Map<String, Object> before = row(ref.code());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertFalse(writes.moveComplianceHoldStart(ref.code(), START, NEXT.plusSeconds(86400)));
            assertFalse(writes.moveComplianceHoldStart("KL-NONE00", START, NEXT));
        });
        assertEquals(before, row(ref.code()));
    }

    /** Kiểm dời mốc không commit độc lập; rollback bên gọi khôi phục nguyên bản ghi đã tồn tại. */
    @Test
    void rollsBackWithCallerTransaction() {
        ReservationRef ref = compliance(VEHICLES.incrementAndGet());
        Map<String, Object> before = row(ref.code());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            directory.moveComplianceHoldStart(ref.code(), NEXT);
            assertOnlyStartChanged(before, NEXT);
            status.setRollbackOnly();
        });
        assertEquals(before, row(ref.code()));
    }

    /**
     * Kiểm hai lần dời cùng đọc mốc cũ: đúng một commit, một CONFLICT, mốc cuối thuộc bên thắng.
     * Đảo thứ tự mốc đề nghị để không chỉ kiểm trường hợp mốc nhỏ hơn luôn được gọi trước.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMovesFromSameStartHaveOneWinner(boolean reverse) throws Exception {
        long vehicleId = VEHICLES.incrementAndGet();
        ReservationRef ref = compliance(vehicleId);
        Map<String, Object> before = row(ref.code());
        Instant firstStart = reverse ? NEXT.plusSeconds(86400) : NEXT;
        Instant secondStart = reverse ? NEXT : NEXT.plusSeconds(86400);
        CyclicBarrier bothReadOldStart = new CyclicBarrier(2);
        AtomicInteger readCount = new AtomicInteger();
        doAnswer(invocation -> {
            Object actual = invocation.callRealMethod();
            if (readCount.incrementAndGet() <= 2) {
                awaitBarrier(bothReadOldStart);
            }
            return actual;
        }).when(reads).loadAggregate(ref.code());

        List<Attempt> attempts = race(
                () -> directory.moveComplianceHoldStart(ref.code(), firstStart),
                () -> directory.moveComplianceHoldStart(ref.code(), secondStart));

        assertEquals(2, readCount.get());
        attempts.forEach(attempt -> assertNoDeadlock(attempt.failure()));
        assertEquals(1, attempts.stream().filter(attempt -> attempt.failure() == null).count());
        int winner = attempts.getFirst().failure() == null ? 0 : 1;
        DomainException loser = assertInstanceOf(DomainException.class, attempts.get(1 - winner).failure());
        assertEquals(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, loser.errorCode());
        assertEquals(DomainException.Category.CONFLICT, loser.category());
        assertOnlyStartChanged(before, winner == 0 ? firstStart : secondStart);
        assertEquals(1, vehicleRows(vehicleId).size());
        assertNoOverlaps(vehicleId);
    }

    /**
     * Lặp 100 cuộc đua dời mốc với hold: dời luôn thành công, hold thành công hoặc VEHICLE_NOT_AVAILABLE.
     * Mọi lỗi hạ tầng, kể cả 40P01, đều làm test đỏ; SQL độc lập kiểm không có hai khóa chồng nhau.
     */
    @Test
    void repeatedMoveAndHoldRacesPreserveSchedule() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            long vehicleId = VEHICLES.incrementAndGet();
            ReservationRef ref = compliance(vehicleId);
            Map<String, Object> before = row(ref.code());
            List<Attempt> attempts = race(
                    () -> directory.moveComplianceHoldStart(ref.code(), NEXT),
                    () -> directory.hold(vehicleId, new Period(START, NEXT), Duration.ZERO, "move-race-booking"));
            attempts.forEach(attempt -> assertNoDeadlock(attempt.failure()));
            assertNull(attempts.getFirst().failure(), "Moving the compliance start must succeed: " + attempts);
            Throwable holdFailure = attempts.getLast().failure();
            if (holdFailure != null) {
                DomainException busy = assertInstanceOf(DomainException.class, holdFailure);
                assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, busy.errorCode());
                assertEquals(DomainException.Category.CONFLICT, busy.category());
            }
            assertOnlyStartChanged(before, NEXT);
            List<Map<String, Object>> rows = vehicleRows(vehicleId);
            assertEquals(holdFailure == null ? 2 : 1, rows.size());
            if (holdFailure == null) {
                Map<String, Object> held = rows.stream().filter(row -> "RENTAL".equals(row.get("kind")))
                        .findFirst().orElseThrow();
                assertEquals("HELD", held.get("status"));
                assertEquals(START, readInstant(held.get("starts_at")));
                assertEquals(NEXT, readInstant(held.get("ends_at")));
            }
            assertNoOverlaps(vehicleId);
        }
    }

    /** Tạo khóa giấy tờ thật đã commit trước khi bắt đầu thao tác hoặc cuộc đua. */
    private ReservationRef compliance(long vehicleId) {
        return directory.block(vehicleId, new Period(START, null), BlockKind.COMPLIANCE_HOLD, "Document renewed");
    }

    /** Đọc toàn bộ trường bằng SQL test riêng, không suy dữ liệu CSDL từ kết quả domain. */
    private Map<String, Object> row(String code) {
        Map<String, Object> result = jdbc.queryForMap(readSql, Map.of("code", code));
        result.put("starts_at", readInstant(result.get("starts_at")));
        return result;
    }

    /** So sánh thời điểm, không phụ thuộc JDBC trả Timestamp hay OffsetDateTime cho timestamptz. */
    private static Instant readInstant(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime timestamp -> timestamp.toInstant();
            default -> throw new AssertionError("Expected a JDBC timestamp, got: " + value);
        };
    }

    /** Đối chiếu đầy đủ trường, gồm ID, cận khoảng, trạng thái và hai mốc thời gian. */
    private void assertOnlyStartChanged(Map<String, Object> before, Instant newStart) {
        Map<String, Object> expected = new LinkedHashMap<>(before);
        expected.put("starts_at", newStart);
        assertEquals(expected, row((String) before.get("code")));
    }

    /** Đọc mọi bản ghi của xe sau khi worker đã commit hoặc rollback. */
    private List<Map<String, Object>> vehicleRows(long vehicleId) {
        return jdbc.queryForList(readVehicleSql, Map.of("vehicleIds", List.of(vehicleId)));
    }

    /** Kiểm không có cặp bản ghi đang chặn chồng nhau bằng phép nối trên PostgreSQL. */
    private void assertNoOverlaps(long vehicleId) {
        assertEquals(0L, jdbc.queryForObject(overlapSql, Map.of("vehicleId", vehicleId), Long.class));
    }

    /** Kiểm đúng lỗi xe bận, không chấp nhận exception SQL hay lỗi đầu vào thay thế. */
    private static void assertVehicleBusy(Runnable operation) {
        DomainException failure = assertThrows(DomainException.class, operation::run);
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }

    /** Mở hai transaction trên hai kết nối thật; chờ kết quả và dừng worker đều có giới hạn. */
    private List<Attempt> race(Runnable first, Runnable second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        Set<Integer> pids = ConcurrentHashMap.newKeySet();
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("compliance-move-race-", 0).daemon(true).factory());
        List<Future<Attempt>> futures = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            futures.add(workers.submit(() -> execute(first, start, pids)));
            futures.add(workers.submit(() -> execute(second, start, pids)));
            List<Attempt> results = List.of(futures.getFirst().get(35, TimeUnit.SECONDS),
                    futures.getLast().get(35, TimeUnit.SECONDS));
            assertEquals(2, pids.size(), "The contenders must use distinct PostgreSQL sessions.");
            return results;
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            futures.forEach(future -> { if (!future.isDone()) { future.cancel(true); } });
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Race workers did not stop.");
            } catch (InterruptedException | AssertionError cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (originalFailure != null) {
                    originalFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Gọi API qua proxy trong transaction của worker; chỉ trả thành công sau commit. */
    private Attempt execute(Runnable operation, CyclicBarrier start, Set<Integer> pids) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(45);
        try {
            transaction.executeWithoutResult(status -> {
                Integer pid = jdbc.queryForObject(prepareSql, Map.of(), (row, index) -> row.getInt("backend_pid"));
                assertNotNull(pid);
                pids.add(pid);
                awaitBarrier(start);
                operation.run();
            });
            return new Attempt(null);
        } catch (RuntimeException failure) {
            return new Attempt(failure);
        }
    }

    /** Chờ chốt có giới hạn; timeout hoặc ngắt phải làm test thất bại, không treo suite. */
    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while coordinating the race.", failure);
        } catch (Exception failure) {
            throw new AssertionError("Could not coordinate the race.", failure);
        }
    }

    /** Kiểm tường minh không có 40P01 lọt qua bất kỳ lớp bọc lỗi nào của Spring. */
    private static void assertNoDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres) {
                assertNotEquals("40P01", postgres.getSQLState(), "Deadlock escaped the savepoint helper.");
            }
        }
    }

    /** Kết quả sau commit/rollback của một bên tranh chấp, không phải kết quả ngay sau UPDATE. */
    private record Attempt(Throwable failure) {
    }
}
