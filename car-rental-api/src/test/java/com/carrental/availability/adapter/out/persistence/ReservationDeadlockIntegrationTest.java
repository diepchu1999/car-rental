package com.carrental.availability.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Dựng deadlock tất định với khóa nằm trước savepoint theo BR-104 và hướng sửa Chief Architect đã duyệt.
 *
 * <p>T1 chèn xe 1, T2 chèn xe 2; chỉ sau khi cả hai đã giữ khóa mới cùng chèn chéo.
 * Rollback savepoint của INSERT chéo không nhả khóa ban đầu, nên phải lặp tới giới hạn.
 * JDBC được bọc để đếm lần gọi nhưng mọi INSERT và mọi lỗi đều do PostgreSQL thật thực hiện.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=5000"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationDeadlockIntegrationTest {

    private static final long VEHICLE_ONE = 9_000_000_500_001L;
    private static final long VEHICLE_TWO = VEHICLE_ONE + 1;
    private static final String SEED_ONE = "KL-SEED01";
    private static final String SEED_TWO = "KL-SEED02";
    private static final String CROSS_ONE = "KL-CROSS1";
    private static final String CROSS_TWO = "KL-CROSS2";

    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader loader;

    private ReservationWriteAdapter observedAdapter;
    private String prepareSql;
    private final Map<String, AtomicInteger> calls = Map.of(
            CROSS_ONE, new AtomicInteger(), CROSS_TWO, new AtomicInteger());
    private final Map<String, List<DataAccessException>> failures = Map.of(
            CROSS_ONE, new ArrayList<>(), CROSS_TWO, new ArrayList<>());
    private final Map<String, List<SqlParameterSource>> parameters = Map.of(
            CROSS_ONE, new ArrayList<>(), CROSS_TWO, new ArrayList<>());

    /** Dựng adapter thật với bộ đếm JDBC chuyển tiếp; không thay lỗi hoặc kết quả của database. */
    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        String sql = loader.load(ReservationSqlPaths.INSERT);
        prepareSql = loader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        NamedParameterJdbcTemplate observingJdbc = mock(NamedParameterJdbcTemplate.class);
        when(observingJdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenAnswer(invocation -> {
                    SqlParameterSource values = invocation.getArgument(1);
                    String code = (String) values.getValue("code");
                    calls.get(code).incrementAndGet();
                    parameters.get(code).add(values);
                    try {
                        return jdbc.queryForList(sql, values, Long.class);
                    } catch (DataAccessException failure) {
                        failures.get(code).add(failure);
                        throw failure;
                    }
                });
        observedAdapter = new ReservationWriteAdapter(observingJdbc, loader, transactionManager);
    }

    /**
     * Kiểm một bên hết đúng ba lượt, giữ nguyên lỗi 40P01 và rollback toàn bộ transaction ngoài.
     *
     * <p>Bên còn lại được commit sau khi khóa ngoài savepoint của bên thua đã nhả.
     * Không giả deadlock thành xe bận và không có lần INSERT thứ tư.
     *
     * @throws Exception nếu điều phối hoặc transaction vượt thời gian cho phép
     */
    @Test
    void persistentDeadlockStopsAfterThreeAttemptsAndRollsBackOuterTransaction() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("persistent-deadlock-", 0).daemon(true).factory());
        CountDownLatch startCrossInsert = new CountDownLatch(1);
        CompletableFuture<Integer> firstReady = new CompletableFuture<>();
        CompletableFuture<Integer> secondReady = new CompletableFuture<>();
        List<Future<Outcome>> futures = new ArrayList<>();
        Throwable testFailure = null;
        try {
            futures.add(workers.submit(() -> insertAcrossVehicles(
                    blocked(SEED_ONE, VEHICLE_ONE), blocked(CROSS_ONE, VEHICLE_TWO), firstReady, startCrossInsert)));
            futures.add(workers.submit(() -> insertAcrossVehicles(
                    blocked(SEED_TWO, VEHICLE_TWO), blocked(CROSS_TWO, VEHICLE_ONE), secondReady, startCrossInsert)));
            int firstPid = firstReady.get(10, TimeUnit.SECONDS);
            int secondPid = secondReady.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstPid, secondPid);
            startCrossInsert.countDown();
            Outcome first = futures.get(0).get(25, TimeUnit.SECONDS);
            Outcome second = futures.get(1).get(25, TimeUnit.SECONDS);
            assertNotEquals(first.failure() == null, second.failure() == null,
                    "Exactly one outer transaction must fail after exhausting deadlock retries.");
            Outcome loser = first.failure() != null ? first : second;
            Outcome winner = first.failure() == null ? first : second;
            assertTrue(winner.id().isPresent());
            assertEquals(3, calls.get(loser.crossCode()).get());
            assertEquals(3, failures.get(loser.crossCode()).size());
            assertSame(failures.get(loser.crossCode()).getFirst(), loser.failure());
            assertDeadlock(loser.failure());
            for (String code : List.of(CROSS_ONE, CROSS_TWO)) {
                assertTrue(calls.get(code).get() >= 1 && calls.get(code).get() <= 3);
                failures.get(code).forEach(ReservationDeadlockIntegrationTest::assertDeadlock);
                for (SqlParameterSource values : parameters.get(code)) {
                    assertSame(parameters.get(code).getFirst(), values);
                }
            }
            assertTrue(reads.loadAggregate(loser.seedCode()).isEmpty(), "Outer seed must roll back too.");
            assertTrue(reads.loadAggregate(loser.crossCode()).isEmpty());
            assertTrue(reads.loadAggregate(winner.seedCode()).isPresent());
            assertTrue(reads.loadAggregate(winner.crossCode()).isPresent());
            System.out.printf("Persistent deadlock: loserAttempts=%d, winnerAttempts=%d, SQLSTATE=40P01%n",
                    calls.get(loser.crossCode()).get(), calls.get(winner.crossCode()).get());
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            for (Future<Outcome> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            workers.shutdownNow();
            startCrossInsert.countDown();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Deadlock workers did not terminate.");
            } catch (InterruptedException | AssertionError cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Giữ seed trong transaction ngoài; INSERT chéo mở savepoint mới nhưng không thể nhả khóa seed. */
    private Outcome insertAcrossVehicles(Reservation seed, Reservation cross,
                                         CompletableFuture<Integer> ready, CountDownLatch start) {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setTimeout(35);
        try {
            OptionalLong id = outer.execute(status -> {
                Integer pid = jdbc.queryForObject(prepareSql, Map.of(), (row, index) -> row.getInt("backend_pid"));
                assertNotNull(pid);
                writes.insert(seed).orElseThrow();
                ready.complete(pid);
                awaitStart(start);
                return observedAdapter.insert(cross);
            });
            assertNotNull(id);
            return new Outcome(seed.code(), cross.code(), id, null);
        } catch (DataAccessException failure) {
            ready.completeExceptionally(failure);
            return new Outcome(seed.code(), cross.code(), OptionalLong.empty(), failure);
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Chờ cả hai seed đã được chèn, không dùng sleep để phỏng đoán thời điểm giữ khóa. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(12, TimeUnit.SECONDS), "Timed out waiting for both outer locks.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for outer locks.", failure);
        }
    }

    /** Chỉ chấp nhận deadlock thật từ PostgreSQL, không chấp nhận timeout hoặc lỗi nghiệp vụ. */
    private static void assertDeadlock(DataAccessException failure) {
        PSQLException postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("40P01", postgres.getSQLState());
    }

    /** Các khóa cùng khoảng để hai lệnh chéo luôn va với seed chưa commit của transaction kia. */
    private static Reservation blocked(String code, long vehicleId) {
        Instant created = Instant.parse("2030-01-01T00:00:00Z");
        Instant start = created.plusSeconds(86400);
        return Reservation.createBlocked(code, vehicleId,
                ReservationPeriod.finite(start, start.plusSeconds(7200)),
                ReservationKind.MAINTENANCE, "Persistent deadlock test", created);
    }

    /** Kết quả chỉ được trả sau khi transaction ngoài commit hoặc rollback hoàn tất. */
    private record Outcome(String seedCode, String crossCode, OptionalLong id, DataAccessException failure) {
    }
}
