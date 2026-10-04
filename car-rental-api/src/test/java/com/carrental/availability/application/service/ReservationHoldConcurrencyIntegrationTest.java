package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm chống trùng lịch dưới đồng thời thật theo BR-104 và ADR-0005.
 *
 * <p>Mọi luồng giữ kết nối PostgreSQL và transaction riêng trước khi mở chốt.
 * Gọi use case thật qua Spring, không mock Clock, bộ sinh mã, policy hoặc adapter.
 * Chỉ tính thành công sau khi TransactionTemplate hoàn tất commit.
 * Timeout, deadlock và lỗi kết nối làm test đỏ, không được tính là từ chối hợp lệ.
 *
 * <p>Không có transaction bao quanh phương thức test. Dữ liệu được commit thật
 * trong container riêng; context và container được đóng sau lớp test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "spring.datasource.hikari.connection-timeout=20000",
        "car-rental.availability.hold-duration=PT1H"
})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ReservationHoldConcurrencyIntegrationTest {

    private static final int CONTENDERS = 50;
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final long VEHICLE_BASE = 9_000_000_000_300L;

    @Autowired
    private HoldReservationUseCase useCase;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader sqlLoader;

    private String prepareSql;
    private String readSql;

    /** Tải SQL quan sát và bảo đảm test không bị bọc trong transaction của Spring Test. */
    @BeforeEach
    void setUp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        prepareSql = sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        readSql = sqlLoader.load("sql/availability/read_committed_holds_for_test.sql");
    }

    /**
     * Kiểm đúng một commit và 49 lỗi VEHICLE_NOT_AVAILABLE trên cùng xe, cùng khoảng.
     *
     * @param bufferHours đệm bằng không hoặc hai giờ, cả hai đều phải chống tranh chấp
     * @throws Exception nếu điều phối luồng, transaction hoặc cơ sở dữ liệu gặp lỗi
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 2})
    void fiftyConcurrentHoldsProduceExactlyOneCommittedWinner(long bufferHours) throws Exception {
        long vehicleId = VEHICLE_BASE + bufferHours;
        List<HoldReservationCommand> commands = IntStream.range(0, CONTENDERS)
                .mapToObj(index -> HoldReservationCommand.from(vehicleId, START, END,
                        Duration.ofHours(bufferHours), "concurrent-booking-" + index))
                .toList();

        List<Attempt> attempts = runTogether(commands);
        List<Attempt> winners = attempts.stream().filter(attempt -> attempt.reference() != null).toList();
        assertEquals(1, winners.size(), "Exactly one transaction must commit.");
        assertEquals(49, attempts.stream().filter(attempt -> attempt.failure() != null).count());
        for (Attempt attempt : attempts) {
            if (attempt.failure() != null) {
                assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, attempt.failure().errorCode());
                assertEquals(DomainException.Category.CONFLICT, attempt.failure().category());
            }
        }

        Attempt winner = winners.getFirst();
        List<StoredHold> rows = readCommitted(List.of(vehicleId));
        assertEquals(1, rows.size(), "Rolled-back attempts must not leave reservation rows.");
        assertStored(winner.command(), winner.reference(), rows.getFirst());
    }

    /**
     * Kiểm hai khoảng [10,12) và [12,14) trên một xe cùng commit khi đệm bằng không.
     *
     * @throws Exception nếu điều phối luồng hoặc transaction thất bại
     */
    @Test
    void concurrentAdjacentPeriodsBothCommit() throws Exception {
        long vehicleId = VEHICLE_BASE + 10;
        List<HoldReservationCommand> commands = List.of(
                HoldReservationCommand.from(vehicleId, START, END, Duration.ZERO, "adjacent-first"),
                HoldReservationCommand.from(vehicleId, END, END.plusSeconds(7200), Duration.ZERO, "adjacent-second")
        );
        assertBothCommitted(commands, List.of(vehicleId));
    }

    /**
     * Kiểm ràng buộc chỉ tranh chấp trên cùng xe, không khóa toàn bộ đội xe.
     *
     * @throws Exception nếu điều phối luồng hoặc transaction thất bại
     */
    @Test
    void concurrentDifferentVehiclesBothCommit() throws Exception {
        List<Long> vehicleIds = List.of(VEHICLE_BASE + 20, VEHICLE_BASE + 21);
        List<HoldReservationCommand> commands = List.of(
                HoldReservationCommand.from(vehicleIds.get(0), START, END, Duration.ofHours(2), "separate-first"),
                HoldReservationCommand.from(vehicleIds.get(1), START, END, Duration.ofHours(2), "separate-second")
        );
        assertBothCommitted(commands, vehicleIds);
    }

    /** Chạy nhóm hai lượt không xung đột rồi đối chiếu cả hai bản ghi đã commit. */
    private void assertBothCommitted(List<HoldReservationCommand> commands, List<Long> vehicleIds) throws Exception {
        List<Attempt> attempts = runTogether(commands);
        List<StoredHold> rows = readCommitted(vehicleIds);
        assertEquals(2, rows.size());
        for (Attempt attempt : attempts) {
            assertNull(attempt.failure());
            assertNotNull(attempt.reference());
            StoredHold row = rows.stream().filter(stored -> stored.code().equals(attempt.reference().code()))
                    .findFirst().orElseThrow();
            assertStored(attempt.command(), attempt.reference(), row);
        }
        assertNotEquals(attempts.get(0).reference(), attempts.get(1).reference());
    }

    /**
     * Giữ kết nối riêng cho mọi luồng, mở chốt chung rồi chờ kết quả có giới hạn.
     *
     * <p>Chốt nằm trước lệnh giữ chỗ nhưng sau khi transaction đã có kết nối.
     * Vì vậy pool không được phép âm thầm nối tiếp hóa 50 yêu cầu thành một luồng.
     *
     * @param commands yêu cầu của từng luồng
     * @return kết quả sau commit hoặc rollback của toàn bộ luồng
     * @throws Exception nếu thiếu kết nối, hết thời gian hoặc có lỗi không phải xe bận
     */
    private List<Attempt> runTogether(List<HoldReservationCommand> commands) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(commands.size(),
                Thread.ofPlatform().name("reservation-hold-test-", 0).daemon(true).factory());
        List<CompletableFuture<Integer>> connections = new ArrayList<>();
        List<Future<Attempt>> results = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            for (HoldReservationCommand command : commands) {
                CompletableFuture<Integer> connectionReady = new CompletableFuture<>();
                connections.add(connectionReady);
                results.add(workers.submit(() -> holdInTransaction(command, connectionReady, start)));
            }

            CompletableFuture.allOf(connections.toArray(CompletableFuture<?>[]::new)).get(30, TimeUnit.SECONDS);
            assertEquals(commands.size(), connections.stream().map(CompletableFuture::join).distinct().count(),
                    "Every contender must hold a distinct PostgreSQL connection before writing.");
            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            for (Future<Attempt> result : results) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "Concurrent holds did not finish within the deadline.");
                attempts.add(result.get(remaining, TimeUnit.NANOSECONDS));
            }
            return attempts;
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            // Hủy worker trước khi nhả chốt nếu test lỗi trong giai đoạn chuẩn bị.
            for (Future<Attempt> result : results) {
                if (!result.isDone()) {
                    result.cancel(true);
                }
            }
            workers.shutdownNow();
            start.countDown();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Concurrent test workers did not stop.");
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

    /**
     * Mở transaction, báo kết nối sẵn sàng và chỉ trả kết quả sau khi commit hoàn tất.
     *
     * @param command yêu cầu giữ chỗ của luồng
     * @param ready tín hiệu PID hoặc lỗi mở transaction
     * @param start chốt bắt đầu chung
     * @return thành công đã commit hoặc lỗi nghiệp vụ đã rollback
     */
    private Attempt holdInTransaction(
            HoldReservationCommand command, CompletableFuture<Integer> ready, CountDownLatch start
    ) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setTimeout(60);
        try {
            ReservationRef reference = transaction.execute(status -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                Integer pid = jdbc.queryForObject(prepareSql, Map.of(), (rs, rowNum) -> rs.getInt("backend_pid"));
                assertNotNull(pid);
                ready.complete(pid);
                awaitStart(start);
                return useCase.hold(command);
            });
            // execute chỉ trả sau khi commit, không tính kết quả trong callback là thành công.
            assertNotNull(reference);
            return new Attempt(command, reference, null);
        } catch (DomainException failure) {
            ready.completeExceptionally(failure);
            assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            return new Attempt(command, null, failure);
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Chờ chốt với giới hạn; bị ngắt hoặc hết thời gian phải khiến transaction rollback. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(35, TimeUnit.SECONDS), "Timed out waiting for concurrent start.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted before concurrent hold.", failure);
        }
    }

    /** Đọc bằng kết nối ngoài các transaction worker sau khi tất cả đã kết thúc. */
    private List<StoredHold> readCommitted(List<Long> vehicleIds) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return jdbc.query(readSql, Map.of("vehicleIds", vehicleIds), (rs, rowNum) -> new StoredHold(
                rs.getLong("id"), rs.getString("code"), rs.getLong("vehicle_id"), rs.getString("booking_code"),
                rs.getString("kind"), rs.getString("status"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("start_inclusive"), rs.getBoolean("end_inclusive")
        ));
    }

    /** Đối chiếu đúng người thắng, xe, mã đơn, trạng thái và khoảng có đệm đã commit. */
    private static void assertStored(HoldReservationCommand command, ReservationRef reference, StoredHold row) {
        assertEquals(reference.id(), row.id());
        assertEquals(reference.code(), row.code());
        assertTrue(row.code().matches("KL-[A-Z0-9]{6}"));
        assertEquals(command.vehicleId(), row.vehicleId());
        assertEquals(command.bookingCode(), row.bookingCode());
        assertEquals("RENTAL", row.kind());
        assertEquals("HELD", row.status());
        assertEquals(command.rentalPeriod().startInclusive(), row.start());
        assertEquals(command.rentalPeriod().endExclusive().plus(command.buffer()), row.end());
        assertTrue(row.startInclusive());
        assertFalse(row.endInclusive());
    }

    /** Kết quả của một luồng sau khi transaction đã kết thúc, không dùng bộ đếm trong callback. */
    private record Attempt(HoldReservationCommand command, ReservationRef reference, DomainException failure) {
    }

    /** Dữ liệu đọc độc lập từ CSDL để đối chiếu với yêu cầu đã commit. */
    private record StoredHold(
            long id, String code, long vehicleId, String bookingCode, String kind, String status,
            Instant start, Instant end, boolean startInclusive, boolean endInclusive
    ) {
    }
}
