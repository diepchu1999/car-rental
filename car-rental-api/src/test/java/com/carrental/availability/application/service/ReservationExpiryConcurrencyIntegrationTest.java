package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.command.ConfirmReservationCommand;
import com.carrental.availability.application.port.in.ConfirmReservationUseCase;
import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Kiểm tranh chấp thật giữa hai lượt dọn và giữa dọn với xác nhận theo BR-103.
 *
 * <p>Hai kết nối ghi giữ transaction đồng thời; kết nối thứ ba quan sát pg_blocking_pids
 * trước khi cho bên đầu commit/rollback. Không sleep để đoán thứ tự và không giả lập SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000",
        "car-rental.availability.hold-duration=PT78M"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationExpiryConcurrencyIntegrationTest {

    private static final Instant EXPIRES = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant BEFORE_EXPIRY = EXPIRES.minusSeconds(1);
    private static final Instant CREATED = EXPIRES.minusSeconds(3600);
    private final ThreadLocal<Instant> invocationTime = ThreadLocal.withInitial(() -> EXPIRES);

    @Autowired
    private ExpireReservationHoldsUseCase expiry;
    @Autowired
    private ConfirmReservationUseCase confirms;
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
    @MockitoBean(name = "applicationClock")
    private Clock clock;

    private TransactionTemplate transactions;
    private NamedParameterJdbcTemplate diagnostics;
    private String prepareSql;
    private String blockedSql;

    /** Tách mốc xác nhận trước hạn và mốc dọn tại hạn; giới hạn thời gian chờ của mọi kết nối. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenAnswer(invocation -> invocationTime.get());
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(40);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        diagnostics = new NamedParameterJdbcTemplate(jdbc);
        prepareSql = sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        blockedSql = sqlLoader.load("sql/availability/is_backend_blocked_by_for_test.sql");
    }

    /**
     * Kiểm hai job không nhả hai lần, xác nhận thắng không bị dọn, dọn thắng chặn xác nhận;
     * rollback bên đầu cho bên chờ hoàn tất bình thường.
     */
    @ParameterizedTest
    @CsvSource({
            "1,false,false,false", "2,false,false,true",
            "3,true,false,false", "4,true,false,true",
            "5,false,true,false", "6,false,true,true"
    })
    void rechecksStateAfterCompetingTransactionEnds(
            int scenario, boolean firstConfirms, boolean secondConfirms, boolean rollbackFirst
    ) throws Exception {
        String code = "KL-ECR00" + scenario;
        Reservation initial = Reservation.createHeld(code, 9_000_000_008_000L + scenario,
                ReservationPeriod.finite(EXPIRES.plusSeconds(86400), EXPIRES.plusSeconds(93600)),
                "expiry-race-" + scenario, Duration.ofHours(1), CREATED);
        transactions.executeWithoutResult(status -> writes.insert(initial).orElseThrow());
        var firstReady = new CompletableFuture<Integer>();
        var secondReady = new CompletableFuture<Integer>();
        var allowFirstCompletion = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("expiry-race-", 0).daemon(true).factory());
        Throwable testFailure = null;
        try {
            var first = workers.submit(() -> transactions.execute(status -> {
                int pid = prepareConnection();
                int count = invoke(firstConfirms, code);
                assertEquals(1, count);
                firstReady.complete(pid);
                awaitCompletion(allowFirstCompletion);
                if (rollbackFirst) {
                    status.setRollbackOnly();
                }
                return count;
            }));
            int firstPid = firstReady.get(10, TimeUnit.SECONDS);
            var second = workers.submit(() -> {
                try {
                    Integer count = transactions.execute(status -> {
                        secondReady.complete(prepareConnection());
                        return invoke(secondConfirms, code);
                    });
                    return new Outcome(count, null);
                } catch (DomainException failure) {
                    return new Outcome(null, failure);
                }
            });
            int secondPid = secondReady.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstPid, secondPid);
            await("The second operation must wait on the first PostgreSQL transaction")
                    .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> {
                        assertFalse(first.isDone(), "First transaction ended before contention was observed.");
                        if (second.isDone()) {
                            throw new AssertionError("Second operation did not wait: " + second.get());
                        }
                        return Boolean.TRUE.equals(diagnostics.queryForObject(blockedSql,
                                Map.of("blockingPid", firstPid, "waitingPid", secondPid), Boolean.class));
                    });
            allowFirstCompletion.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS).intValue());
            Outcome outcome = second.get(15, TimeUnit.SECONDS);
            if (secondConfirms && !rollbackFirst) {
                assertNull(outcome.count());
                assertNotNull(outcome.failure());
                assertEquals(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, outcome.failure().errorCode());
                assertEquals(DomainException.Category.CONFLICT, outcome.failure().category());
            } else {
                assertNull(outcome.failure());
                assertEquals(rollbackFirst ? 1 : 0, outcome.count().intValue());
            }
            boolean confirmed = rollbackFirst ? secondConfirms : firstConfirms;
            Reservation stored = reads.loadAggregate(code).orElseThrow();
            assertEquals(confirmed ? ReservationStatus.CONFIRMED : ReservationStatus.RELEASED, stored.status());
            assertEquals(confirmed ? BEFORE_EXPIRY : EXPIRES, stored.statusChangedAt());
            assertEquals(initial.period(), stored.period());
            assertEquals(initial.holdExpiresAt(), stored.holdExpiresAt());
            assertEquals(initial.createdAt(), stored.createdAt());
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            allowFirstCompletion.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Expiry workers must terminate.");
            } catch (Exception | Error cleanupFailure) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Gọi use case qua proxy, với mốc riêng của mỗi thao tác nhưng cùng bean applicationClock. */
    private int invoke(boolean confirm, String code) {
        invocationTime.set(confirm ? BEFORE_EXPIRY : EXPIRES);
        try {
            if (confirm) {
                confirms.confirm(ConfirmReservationCommand.from(code));
                return 1;
            }
            return expiry.expireHolds();
        } finally {
            invocationTime.remove();
        }
    }

    /** Cài timeout trong transaction và lấy PID để chứng minh dùng hai phiên PostgreSQL khác nhau. */
    private int prepareConnection() {
        return diagnostics.queryForObject(prepareSql, Map.of(), (row, number) -> row.getInt("backend_pid"));
    }

    /** Giữ transaction đầu mở cho tới khi đã quan sát được chờ khóa thật. */
    private static void awaitCompletion(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "First transaction was not released in time.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Expiry concurrency test interrupted.", failure);
        }
    }

    /** Kết quả sau khi transaction thứ hai kết thúc, không biến lỗi hạ tầng thành lỗi nghiệp vụ. */
    private record Outcome(Integer count, DomainException failure) {
    }
}
