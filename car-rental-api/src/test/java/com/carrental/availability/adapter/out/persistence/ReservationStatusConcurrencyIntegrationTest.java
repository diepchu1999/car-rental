package com.carrental.availability.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm hai transaction tranh chấp cùng trạng thái HELD theo BR-103 và status-flow §2.
 *
 * <p>Quan sát PostgreSQL xác nhận chờ khóa thật trước khi cho transaction đầu kết thúc.
 * Commit khiến bên chờ không còn khớp trạng thái; rollback cho phép bên chờ cập nhật.
 * Không dùng transaction bao quanh test; container riêng được đóng sau lớp test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationStatusConcurrencyIntegrationTest {

    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant FIRST_CHANGE = CREATED.plusSeconds(60);
    private static final Instant SECOND_CHANGE = CREATED.plusSeconds(90);
    private static final int WAIT_SECONDS = 10;

    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private SqlLoader sqlLoader;

    private TransactionTemplate transactions;
    private NamedParameterJdbcTemplate diagnostics;
    private String prepareSql;
    private String blockedSql;

    /** Chuẩn bị transaction, truy vấn quan sát có timeout; không thay cấu hình JDBC của adapter. */
    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(45);
        JdbcTemplate diagnosticJdbc = new JdbcTemplate(dataSource);
        diagnosticJdbc.setQueryTimeout(3);
        diagnostics = new NamedParameterJdbcTemplate(diagnosticJdbc);
        prepareSql = sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        blockedSql = sqlLoader.load("sql/availability/is_backend_blocked_by_for_test.sql");
    }

    /**
     * Kiểm lại điều kiện HELD sau khi transaction giữ khóa commit hoặc rollback.
     *
     * @param scenario số phân biệt dữ liệu đã commit của từng lượt
     * @param firstTarget đích của transaction đầu; bên thứ hai chọn đích còn lại
     * @param rollbackFirst true nếu transaction đầu chủ động rollback
     * @throws Exception nếu điều phối hoặc transaction thất bại
     */
    @ParameterizedTest
    @MethodSource("contendingTransitions")
    void rechecksOldStatusAfterCompetingTransactionEnds(
            int scenario, ReservationStatus firstTarget, boolean rollbackFirst
    ) throws Exception {
        Instant start = Instant.parse("2030-10-01T03:00:00Z");
        Reservation initial = Reservation.createHeld("KL-CAS00" + scenario,
                9_000_000_000_600L + scenario,
                ReservationPeriod.finite(start, start.plusSeconds(14400)),
                "competing-booking-" + scenario, Duration.ofHours(1), CREATED);
        Reservation firstState = firstTarget == ReservationStatus.CONFIRMED
                ? initial.confirm(FIRST_CHANGE) : initial.release(FIRST_CHANGE);
        Reservation secondState = firstTarget == ReservationStatus.CONFIRMED
                ? initial.release(SECOND_CHANGE) : initial.confirm(SECOND_CHANGE);
        transactions.executeWithoutResult(status -> writes.insert(initial).orElseThrow());

        CountDownLatch allowFirstCompletion = new CountDownLatch(1);
        CompletableFuture<Integer> firstReady = new CompletableFuture<>();
        CompletableFuture<Integer> secondReady = new CompletableFuture<>();
        ExecutorService workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("reservation-status-test-", 0).daemon(true).factory());
        Throwable testFailure = null;
        try {
            Future<Boolean> first = workers.submit(() -> updateFirst(
                    initial, firstState, rollbackFirst, firstReady, allowFirstCompletion));
            int firstPid = firstReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Future<Boolean> second = workers.submit(() -> updateSecond(initial, secondState, secondReady));
            int secondPid = secondReady.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotEquals(firstPid, secondPid, "Expected distinct PostgreSQL sessions.");

            await("Second update is blocked by the first transaction")
                    .atMost(Duration.ofSeconds(WAIT_SECONDS))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        if (first.isDone() || second.isDone()) {
                            if (first.isDone()) {
                                first.get(WAIT_SECONDS, TimeUnit.SECONDS);
                            }
                            if (second.isDone()) {
                                second.get(WAIT_SECONDS, TimeUnit.SECONDS);
                            }
                            throw new AssertionError("A transaction finished before lock contention was observed.");
                        }
                        return isBlockedBy(secondPid, firstPid);
                    });

            allowFirstCompletion.countDown();
            assertTrue(first.get(WAIT_SECONDS, TimeUnit.SECONDS), "The first UPDATE must affect one row.");
            assertEquals(rollbackFirst, second.get(WAIT_SECONDS, TimeUnit.SECONDS).booleanValue());
            assertStored(rollbackFirst ? secondState : firstState);
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            allowFirstCompletion.countDown();
            try {
                stopWorkers(workers);
            } catch (RuntimeException | Error cleanupFailure) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Ghi trước và giữ khóa đến khi luồng test quan sát được tranh chấp; trả sau commit/rollback. */
    private boolean updateFirst(Reservation initial, Reservation next, boolean rollback,
                                CompletableFuture<Integer> ready, CountDownLatch allowCompletion) {
        try {
            Boolean result = transactions.execute(status -> {
                int pid = prepareConnection();
                boolean updated = writes.updateStatus(initial.code(), initial.status(),
                        next.status(), next.statusChangedAt());
                assertTrue(updated, "The first transaction must update the reservation.");
                ready.complete(pid);
                waitForCompletionPermission(allowCompletion);
                if (rollback) {
                    status.setRollbackOnly();
                }
                return updated;
            });
            assertNotNull(result);
            return result;
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Báo PID trước UPDATE để luồng test quan sát được việc chờ khóa; trả sau commit. */
    private boolean updateSecond(Reservation initial, Reservation next, CompletableFuture<Integer> ready) {
        try {
            Boolean result = transactions.execute(status -> {
                ready.complete(prepareConnection());
                return writes.updateStatus(initial.code(), initial.status(), next.status(), next.statusChangedAt());
            });
            assertNotNull(result);
            return result;
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Đặt timeout trong transaction hiện tại và lấy PID của chính kết nối đó. */
    private int prepareConnection() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        Integer pid = diagnostics.queryForObject(prepareSql, new MapSqlParameterSource(),
                (row, index) -> row.getInt("backend_pid"));
        assertNotNull(pid);
        return pid;
    }

    /** Dùng kết nối thứ ba xác nhận đúng phiên đang chặn UPDATE thứ hai. */
    private boolean isBlockedBy(int waitingPid, int blockingPid) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("waitingPid", waitingPid, Types.INTEGER)
                .addValue("blockingPid", blockingPid, Types.INTEGER);
        return Boolean.TRUE.equals(diagnostics.queryForObject(blockedSql, parameters, Boolean.class));
    }

    /** Chờ có giới hạn; hết giờ hoặc bị ngắt phải thất bại và rollback transaction. */
    private static void waitForCompletionPermission(CountDownLatch permission) {
        try {
            assertTrue(permission.await(30, TimeUnit.SECONDS), "Timed out waiting to finish the first transaction.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding the first transaction.", failure);
        }
    }

    /** Kiểm trạng thái đã commit và toàn bộ dữ liệu khác, gồm khoảng đệm và hạn giữ chỗ. */
    private void assertStored(Reservation expected) {
        Reservation actual = reads.loadAggregate(expected.code()).orElseThrow();
        assertEquals(expected.code(), actual.code());
        assertEquals(expected.vehicleId(), actual.vehicleId());
        assertEquals(expected.period(), actual.period());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.bookingCode(), actual.bookingCode());
        assertEquals(expected.reason(), actual.reason());
        assertEquals(expected.holdExpiresAt(), actual.holdExpiresAt());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.statusChangedAt(), actual.statusChangedAt());
    }

    /** Dừng worker có giới hạn; không để lỗi dọn dẹp che lỗi gốc của test. */
    private static void stopWorkers(ExecutorService workers) {
        workers.shutdownNow();
        try {
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Workers did not terminate in time.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while stopping transaction workers.", failure);
        }
    }

    /** Cả hai thứ tự xác nhận/nhả chỗ, mỗi thứ tự kiểm cả commit và rollback của bên giữ khóa. */
    private static Stream<Arguments> contendingTransitions() {
        return Stream.of(
                Arguments.of(1, ReservationStatus.CONFIRMED, false),
                Arguments.of(2, ReservationStatus.RELEASED, false),
                Arguments.of(3, ReservationStatus.CONFIRMED, true),
                Arguments.of(4, ReservationStatus.RELEASED, true)
        );
    }
}
