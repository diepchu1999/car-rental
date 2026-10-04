package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.BlockReservationUseCase;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm hold tranh chấp với block hoặc confirm qua ràng buộc chống chồng lịch theo BR-104, ADR-0005.
 *
 * <p>Hai transaction giữ hai kết nối PostgreSQL khác nhau rồi cùng được mở chốt.
 * Không mock dependency; chỉ tính thắng sau commit. Context riêng được đóng sau lớp test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationBlockConcurrencyIntegrationTest {

    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final long VEHICLE_BASE = 9_000_000_000_900L;

    @Autowired
    private BlockReservationUseCase block;
    @Autowired
    private HoldReservationUseCase hold;
    @Autowired
    private ConfirmReservationUseCase confirm;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader sqlLoader;
    private String prepareSql;
    private String readSql;

    /** Dùng SQL quan sát có sẵn; kiểm không có transaction bao quanh test. */
    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        prepareSql = sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        readSql = sqlLoader.load("sql/availability/read_committed_holds_for_test.sql");
    }

    /**
     * Kiểm mỗi loại vận hành tranh chấp với đơn thuê: đúng một commit, một VEHICLE_NOT_AVAILABLE.
     *
     * @param kind nguyên nhân vận hành; compliance dùng khoảng không chặn trên
     * @throws Exception nếu điều phối, kết nối hoặc transaction thất bại
     */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void operationalBlockAndHoldCannotBothCommit(ReservationKind kind) throws Exception {
        long vehicleId = VEHICLE_BASE + kind.ordinal();
        Instant blockEnd = kind == ReservationKind.COMPLIANCE_HOLD ? null : END;
        List<Attempt> attempts = runTogether(List.of(
                () -> block.block(BlockReservationCommand.from(vehicleId, START, blockEnd, kind, "Operational block")),
                () -> hold.hold(HoldReservationCommand.from(vehicleId, START, END, Duration.ZERO, "concurrent-booking"))
        ));
        int winner = assertOneCommittedRow(attempts, vehicleId);
        Reservation actual = reads.loadAggregate(attempts.get(winner).reference().code()).orElseThrow();
        assertEquals(winner == 0 ? kind : ReservationKind.RENTAL, actual.kind());
        assertEquals(winner == 0 ? ReservationStatus.BLOCKED : ReservationStatus.HELD, actual.status());
        assertEquals(START, actual.period().startInclusive());
        assertEquals(winner == 0 ? blockEnd : END, actual.period().endExclusive());
        assertEquals(winner == 0 ? "Operational block" : null, actual.reason());
        assertEquals(winner == 0 ? null : "concurrent-booking", actual.bookingCode());
        if (winner == 0) {
            assertNull(actual.holdExpiresAt());
        } else {
            assertNotNull(actual.holdExpiresAt());
            assertTrue(actual.holdExpiresAt().isAfter(actual.createdAt()));
        }
        assertEquals(actual.createdAt(), actual.statusChangedAt());
    }

    /**
     * Kiểm hai khóa vận hành khác loại cũng không được cùng commit trên một xe và khoảng chồng nhau.
     *
     * @throws Exception nếu điều phối hoặc transaction thất bại
     */
    @Test
    void overlappingOperationalBlocksCannotBothCommit() throws Exception {
        long vehicleId = VEHICLE_BASE + 20;
        List<Attempt> attempts = runTogether(List.of(
                () -> block.block(BlockReservationCommand.from(vehicleId, START, END, ReservationKind.MAINTENANCE, "Workshop")),
                () -> block.block(BlockReservationCommand.from(vehicleId, START, null, ReservationKind.COMPLIANCE_HOLD, "Document"))
        ));
        int winner = assertOneCommittedRow(attempts, vehicleId);
        Reservation actual = reads.loadAggregate(attempts.get(winner).reference().code()).orElseThrow();
        assertEquals(ReservationStatus.BLOCKED, actual.status());
        assertEquals(winner == 0 ? ReservationKind.MAINTENANCE : ReservationKind.COMPLIANCE_HOLD, actual.kind());
        assertEquals(START, actual.period().startInclusive());
        assertEquals(winner == 0 ? END : null, actual.period().endExclusive());
        assertNull(actual.holdExpiresAt());
        assertNull(actual.bookingCode());
    }

    /**
     * Lặp cuộc đua với xe mới từng lượt để phát hiện deadlock hiếm, không che lỗi bằng chạy lại test.
     *
     * <p>Mỗi lượt lỗi được giữ lại, báo số deadlock/loại lỗi khác; cuối cùng buộc không có lượt đỏ.
     * Có thể tăng số lượt bằng -Dreservation.race.iterations, mặc định 100.
     */
    @Test
    void repeatedBlockAndHoldRacesHaveNoInfrastructureFailures() {
        int iterations = Integer.getInteger("reservation.race.iterations", 100);
        assertTrue(iterations > 0, "Race iterations must be positive.");
        int deadlocks = 0;
        List<Throwable> failures = new ArrayList<>();
        for (int index = 0; index < iterations; index++) {
            long vehicleId = VEHICLE_BASE + 10_000 + index;
            try {
                List<Attempt> attempts = runTogether(List.of(
                        () -> block.block(BlockReservationCommand.from(vehicleId, START, END,
                                ReservationKind.MAINTENANCE, "Repeated race")),
                        () -> hold.hold(HoldReservationCommand.from(vehicleId, START, END,
                                Duration.ZERO, "repeated-race-booking"))
                ));
                assertOneCommittedRow(attempts, vehicleId);
            } catch (Exception | AssertionError failure) {
                failures.add(failure);
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof PSQLException postgres && "40P01".equals(postgres.getSQLState())) {
                        deadlocks++;
                        break;
                    }
                }
            }
        }
        System.out.printf("Reservation races: total=%d, failed=%d, deadlocks=%d, other=%d%n",
                iterations, failures.size(), deadlocks, failures.size() - deadlocks);
        if (!failures.isEmpty()) {
            AssertionError summary = new AssertionError("Reservation races failed: " + failures.size() + "/" + iterations);
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    /**
     * Lặp ít nhất 100 cuộc đua confirm/hold với xe mới, giữ nguyên proxy use case và PostgreSQL thật.
     *
     * <p>HELD gốc đã commit trước cuộc đua: confirm phải thành công, hold phải báo xe bận.
     * Không chấp nhận deadlock lọt ra; đọc độc lập sau commit chỉ có bản gốc CONFIRMED.
     * Mỗi lượt thất bại được ghi nhận, không chạy lại lượt đỏ để che lỗi.
     * Có thể tăng số lượt bằng -Dreservation.race.iterations.
     */
    @Test
    void repeatedConfirmAndHoldRacesHaveNoInfrastructureFailures() {
        int iterations = Integer.getInteger("reservation.race.iterations", 100);
        assertTrue(iterations >= 100, "Confirm/hold race requires at least 100 iterations.");
        int deadlocks = 0;
        List<Throwable> failures = new ArrayList<>();
        for (int index = 0; index < iterations; index++) {
            long vehicleId = VEHICLE_BASE + 1_000_000 + index;
            try {
                ReservationRef original = hold.hold(HoldReservationCommand.from(vehicleId, START, END,
                        Duration.ZERO, "confirm-race-original"));
                Reservation before = reads.loadAggregate(original.code()).orElseThrow();
                assertEquals(ReservationStatus.HELD, before.status());
                List<Attempt> attempts = runTogether(List.of(
                        () -> {
                            confirm.confirm(ConfirmReservationCommand.from(original.code()));
                            return original;
                        },
                        () -> hold.hold(HoldReservationCommand.from(vehicleId, START, END,
                                Duration.ZERO, "confirm-race-contender"))
                ));
                assertEquals(0, assertOneCommittedRow(attempts, vehicleId),
                        "Confirmation must commit; the overlapping hold must fail.");
                var rows = jdbc.queryForList(readSql, Map.of("vehicleIds", List.of(vehicleId)));
                assertEquals(1, rows.size());
                assertEquals(original.code(), rows.getFirst().get("code"));
                assertEquals("CONFIRMED", rows.getFirst().get("status"));
                Reservation after = reads.loadAggregate(original.code()).orElseThrow();
                assertEquals(ReservationStatus.CONFIRMED, after.status());
                assertEquals(before.period(), after.period());
                assertEquals(before.holdExpiresAt(), after.holdExpiresAt());
                assertEquals(before.createdAt(), after.createdAt());
                assertEquals(before.bookingCode(), after.bookingCode());
            } catch (Exception | AssertionError failure) {
                failures.add(failure);
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof PSQLException postgres && "40P01".equals(postgres.getSQLState())) {
                        deadlocks++;
                        break;
                    }
                }
            }
        }
        System.out.printf("Confirm/hold races: total=%d, failed=%d, deadlocks=%d, other=%d%n",
                iterations, failures.size(), deadlocks, failures.size() - deadlocks);
        if (!failures.isEmpty()) {
            AssertionError summary = new AssertionError("Confirm/hold races failed: " + failures.size() + "/" + iterations);
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    /** Kiểm thắng sau commit, thua đúng lỗi nghiệp vụ và SQL độc lập chỉ thấy đúng một bản ghi. */
    private int assertOneCommittedRow(List<Attempt> attempts, long vehicleId) {
        assertEquals(2, attempts.size());
        assertEquals(1, attempts.stream().filter(attempt -> attempt.reference() != null).count());
        assertEquals(1, attempts.stream().filter(attempt -> attempt.failure() != null).count());
        int winner = attempts.getFirst().reference() != null ? 0 : 1;
        DomainException failure = attempts.get(1 - winner).failure();
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        var rows = jdbc.queryForList(readSql, Map.of("vehicleIds", List.of(vehicleId)));
        assertEquals(1, rows.size(), "Only the committed winner may leave a reservation row.");
        ReservationRef ref = attempts.get(winner).reference();
        assertEquals(ref.code(), rows.getFirst().get("code"));
        assertEquals(ref.id(), ((Number) rows.getFirst().get("id")).longValue());
        assertTrue(ref.code().matches("KL-[A-Z0-9]{6}"));
        return winner;
    }

    /** Mở chốt sau khi cả hai worker có kết nối thật; chờ kết quả và dọn worker đều có giới hạn. */
    private List<Attempt> runTogether(List<Supplier<ReservationRef>> operations) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("reservation-block-test-", 0).daemon(true).factory());
        List<CompletableFuture<Integer>> connections = new ArrayList<>();
        List<Future<Attempt>> results = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            for (Supplier<ReservationRef> operation : operations) {
                CompletableFuture<Integer> ready = new CompletableFuture<>();
                connections.add(ready);
                results.add(workers.submit(() -> executeInTransaction(operation, ready, start)));
            }
            CompletableFuture.allOf(connections.toArray(CompletableFuture<?>[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(2, connections.stream().map(CompletableFuture::join).distinct().count(),
                    "Both contenders must hold distinct PostgreSQL sessions.");
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (Future<Attempt> result : results) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "Concurrent reservations exceeded the deadline.");
                attempts.add(result.get(remaining, TimeUnit.NANOSECONDS));
            }
            return attempts;
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            for (Future<Attempt> result : results) {
                if (!result.isDone()) {
                    result.cancel(true);
                }
            }
            workers.shutdownNow();
            start.countDown();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Concurrent workers did not stop.");
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

    /** Gọi proxy use case trong transaction của worker và chỉ trả kết quả khi commit/rollback xong. */
    private Attempt executeInTransaction(Supplier<ReservationRef> operation,
                                         CompletableFuture<Integer> ready, CountDownLatch start) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setTimeout(45);
        try {
            ReservationRef ref = transaction.execute(status -> {
                Integer pid = jdbc.queryForObject(prepareSql, Map.of(), (row, index) -> row.getInt("backend_pid"));
                assertNotNull(pid);
                ready.complete(pid);
                awaitStart(start);
                return operation.get();
            });
            assertNotNull(ref);
            return new Attempt(ref, null);
        } catch (DomainException failure) {
            ready.completeExceptionally(failure);
            assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            return new Attempt(null, failure);
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Không cho chờ vô hạn; ngắt hoặc timeout phải khiến transaction thất bại. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(15, TimeUnit.SECONDS), "Timed out before concurrent start.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted before concurrent reservation.", failure);
        }
    }

    /** Kết quả chỉ được tạo sau khi transaction hoàn tất, không đếm thành công ngay sau INSERT. */
    private record Attempt(ReservationRef reference, DomainException failure) {
    }
}
