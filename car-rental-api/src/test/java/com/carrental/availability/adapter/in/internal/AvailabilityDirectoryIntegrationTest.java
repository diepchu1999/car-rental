package com.carrental.availability.adapter.in.internal;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Kiểm hợp đồng cross-module qua Directory, use case và PostgreSQL thật theo BR-104, ADR-0005.
 * Chỉ thay applicationClock để kiểm thời gian; mọi thao tác nghiệp vụ đi qua Directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT79M")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AvailabilityDirectoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant START = NOW.plusSeconds(86400);
    private static final Instant END = START.plusSeconds(7200);
    private static final Period PERIOD = new Period(START, END);
    private static final long VEHICLE_ID = 9_000_000_009_001L;

    @Autowired
    private AvailabilityDirectory directory;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader sqlLoader;
    @MockitoBean(name = "applicationClock")
    private Clock clock;

    /** Cố định Clock chung để kiểm hạn và chuyển trạng thái mà không chờ đồng hồ thật. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    /** Kiểm Spring cung cấp duy nhất một implementation của hợp đồng cross-module. */
    @Test
    void wiresExactlyOneDirectory() {
        assertEquals(1, context.getBeansOfType(AvailabilityDirectory.class).size());
        assertSame(directory, context.getBean(AvailabilityDirectory.class));
    }

    /** Kiểm hold, confirm, markInUse, complete và tra cứu qua API giữ đúng TTL cùng phần đệm. */
    @Test
    void rentalLifecyclePreservesFrozenExpiryAndBuffer() {
        var ref = directory.hold(VEHICLE_ID, PERIOD, Duration.ofHours(2), "directory-booking");
        assertTrue(ref.id() > 0);
        assertTrue(ref.code().matches("KL-[A-Z0-9]{6}"));
        var original = reads.loadAggregate(ref.code()).orElseThrow();
        assertEquals(ReservationStatus.HELD, original.status());
        assertEquals(NOW.plus(Duration.ofMinutes(79)), original.holdExpiresAt());
        assertEquals(END.plusSeconds(7200), original.period().endExclusive());
        directory.confirm(ref.code());
        assertEquals(ReservationStatus.CONFIRMED, reads.loadAggregate(ref.code()).orElseThrow().status());
        directory.markInUse(ref.code());
        assertEquals(ReservationStatus.IN_USE, reads.loadAggregate(ref.code()).orElseThrow().status());
        directory.complete(ref.code());
        var completed = reads.loadAggregate(ref.code()).orElseThrow();
        assertEquals(ReservationStatus.COMPLETED, completed.status());
        assertEquals(original.period(), completed.period());
        assertEquals(original.holdExpiresAt(), completed.holdExpiresAt());
        assertEquals(Set.of(VEHICLE_ID), directory.findBusyVehicleIds(
                new Period(END, END.plusSeconds(3600)), Duration.ZERO, List.of(VEHICLE_ID)));
        assertTrue(directory.findBusyVehicleIds(new Period(END.plusSeconds(7200), END.plusSeconds(10800)),
                Duration.ZERO, List.of(VEHICLE_ID)).isEmpty());
    }

    /** Kiểm release cho cả HELD và CONFIRMED giải phóng lịch qua API công khai. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releaseMakesHeldOrConfirmedVehicleFree(boolean confirmFirst) {
        var ref = directory.hold(VEHICLE_ID, PERIOD, Duration.ZERO, "release-booking");
        if (confirmFirst) {
            directory.confirm(ref.code());
        }
        directory.release(ref.code());
        assertEquals(ReservationStatus.RELEASED, reads.loadAggregate(ref.code()).orElseThrow().status());
        assertTrue(directory.findBusyVehicleIds(PERIOD, Duration.ZERO, List.of(VEHICLE_ID)).isEmpty());
    }

    /** Kiểm năm loại vận hành được lưu đúng, giữ nguyên lý do và hoàn tất qua cùng API. */
    @ParameterizedTest
    @EnumSource(BlockKind.class)
    void storesAndCompletesEveryBlockKind(BlockKind kind) {
        var ref = directory.block(VEHICLE_ID, PERIOD, kind, " Scheduled work ");
        var stored = reads.loadAggregate(ref.code()).orElseThrow();
        assertEquals(ReservationKind.valueOf(kind.name()), stored.kind());
        assertEquals(ReservationStatus.BLOCKED, stored.status());
        assertEquals(" Scheduled work ", stored.reason());
        assertNull(stored.holdExpiresAt());
        assertEquals(END, stored.period().endExclusive());
        directory.complete(ref.code());
        assertEquals(ReservationStatus.COMPLETED, reads.loadAggregate(ref.code()).orElseThrow().status());
        assertEquals(Set.of(VEHICLE_ID), directory.findBusyVehicleIds(PERIOD, Duration.ZERO, List.of(VEHICLE_ID)));
    }

    /** Kiểm COMPLIANCE_HOLD vô hạn giữ cận trên null và chặn truy vấn ở tương lai xa. */
    @Test
    void supportsUnboundedComplianceThroughPublicApi() {
        var ref = directory.block(VEHICLE_ID, new Period(START, null), BlockKind.COMPLIANCE_HOLD, null);
        assertTrue(reads.loadAggregate(ref.code()).orElseThrow().period().isUnbounded());
        assertEquals(Set.of(VEHICLE_ID), directory.findBusyVehicleIds(
                new Period(START.plus(Duration.ofDays(3650)), END.plus(Duration.ofDays(3650))),
                Duration.ZERO, List.of(VEHICLE_ID)));
    }

    /** Kiểm lỗi overlap thật không bị lớp nối biến thành lỗi khác hoặc kết quả rỗng. */
    @Test
    void exposesActualOverlapAsVehicleNotAvailable() {
        directory.block(VEHICLE_ID, PERIOD, BlockKind.MAINTENANCE, null);
        var failure = assertThrows(DomainException.class,
                () -> directory.hold(VEHICLE_ID, PERIOD, Duration.ZERO, "conflicting-booking"));
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
    }

    /** Kiểm hold và block cùng tham gia transaction bên gọi, rollback không để lại khóa mồ côi. */
    @Test
    void callerRollbackRemovesBothHoldAndBlock() {
        var held = directory.hold(VEHICLE_ID, PERIOD, Duration.ZERO, "rollback-booking");
        var blocked = directory.block(VEHICLE_ID + 1, PERIOD, BlockKind.TRANSFER, null);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertTrue(reads.loadAggregate(held.code()).isEmpty());
        assertTrue(reads.loadAggregate(blocked.code()).isEmpty());
    }

    /** Kiểm hai lời gọi API trên hai kết nối thật chỉ có một bên commit; bên thua đúng lỗi xe bận. */
    @Test
    void concurrentPublicCallsHaveOneCommittedWinner() throws Exception {
        TestTransaction.end();
        long vehicleId = VEHICLE_ID + 100;
        var barrier = new CyclicBarrier(2);
        Set<Integer> pids = ConcurrentHashMap.newKeySet();
        String prepareSql = sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql");
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("directory-race-", 0).daemon(true).factory());
        Throwable testFailure = null;
        try {
            var first = workers.submit(() -> holdConcurrently(vehicleId, "api-race-first", barrier, pids, prepareSql));
            var second = workers.submit(() -> holdConcurrently(vehicleId, "api-race-second", barrier, pids, prepareSql));
            List<Attempt> results = List.of(first.get(35, TimeUnit.SECONDS), second.get(35, TimeUnit.SECONDS));
            assertEquals(2, pids.size());
            assertEquals(1, results.stream().filter(result -> result.ref() != null).count());
            assertEquals(1, results.stream().filter(result -> result.failure() != null).count());
            Attempt winner = results.stream().filter(result -> result.ref() != null).findFirst().orElseThrow();
            var failure = results.stream().filter(result -> result.failure() != null).findFirst().orElseThrow().failure();
            assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            assertEquals(ReservationStatus.HELD, reads.loadAggregate(winner.ref().code()).orElseThrow().status());
            assertEquals(Set.of(vehicleId), directory.findBusyVehicleIds(PERIOD, Duration.ZERO, List.of(vehicleId)));
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Directory workers must terminate.");
            } catch (Exception | Error cleanupFailure) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /** Chỉ trả thành công sau commit; lỗi hạ tầng hoặc điều phối phải làm Future thất bại. */
    private Attempt holdConcurrently(long vehicleId, String bookingCode, CyclicBarrier barrier,
                                     Set<Integer> pids, String prepareSql) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(30);
        try {
            ReservationRef ref = transaction.execute(status -> {
                pids.add(jdbc.queryForObject(prepareSql, Map.of(), (row, number) -> row.getInt("backend_pid")));
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Directory race interrupted.", failure);
                } catch (Exception failure) {
                    throw new AssertionError("Directory race failed to synchronize.", failure);
                }
                return directory.hold(vehicleId, PERIOD, Duration.ZERO, bookingCode);
            });
            return new Attempt(ref, null);
        } catch (DomainException failure) {
            return new Attempt(null, failure);
        }
    }

    /** Kết quả một lời gọi API sau khi transaction kết thúc. */
    private record Attempt(ReservationRef ref, DomainException failure) {
    }
}
