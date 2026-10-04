package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.in.ListBusyVehiclesUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.application.query.ListBusyVehiclesQuery;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm toàn luồng đọc qua Spring, Native SQL và PostgreSQL thật theo BR-103, BR-104,
 * BR-109, BR-116; không mock dữ liệu lịch, đồng hồ hoặc kết quả truy vấn.
 *
 * <p>Các ca tuần tự rollback sau mỗi test. Ca hai luồng dùng xe riêng và transaction
 * thật để chứng minh kết quả đọc không bảo đảm giữ được chỗ ở thao tác tiếp theo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationBusyQueryIntegrationTest {

    private static final long VEHICLE_ID = 9_000_000_000_601L;
    private static final Instant CREATED = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);

    @Autowired
    private ListBusyVehiclesUseCase queries;
    @Autowired
    private HoldReservationUseCase holds;
    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Kiểm chính xác năm trạng thái đang chặn và RELEASED không chặn, cùng khoảng và xe. */
    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void matchesBlockingStatuses(ReservationStatus status) {
        ReservationKind kind = status == ReservationStatus.BLOCKED
                ? ReservationKind.MAINTENANCE : ReservationKind.RENTAL;
        writes.insert(Reservation.restore("KL-BUSY01", VEHICLE_ID,
                ReservationPeriod.finite(START, END), kind, status,
                kind == ReservationKind.RENTAL ? "busy-query-booking" : null,
                null, kind == ReservationKind.RENTAL ? CREATED.plusSeconds(3600) : null,
                CREATED, CREATED)).orElseThrow();
        Set<Long> expected = status == ReservationStatus.RELEASED ? Set.of() : Set.of(VEHICLE_ID);
        assertEquals(expected, busy(START, END, Duration.ZERO, VEHICLE_ID));
    }

    /** Kiểm mọi loại khóa vận hành đều được tính bận, không chỉ đơn thuê và bảo dưỡng. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void includesEveryOperationalKind(ReservationKind kind) {
        ReservationPeriod period = kind == ReservationKind.COMPLIANCE_HOLD
                ? ReservationPeriod.unboundedFrom(START) : ReservationPeriod.finite(START, END);
        writes.insert(Reservation.createBlocked("KL-BUSY01", VEHICLE_ID,
                period, kind, null, CREATED)).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), busy(START, END, Duration.ZERO, VEHICLE_ID));
    }

    /** Kiểm HELD cũ đã hết hạn vẫn báo bận và truy vấn không tự sửa trạng thái hay hạn. */
    @Test
    void expiredHeldRemainsBusyUntilReleased() {
        Reservation held = held("KL-BUSY01", VEHICLE_ID, START, END);
        writes.insert(held).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), busy(START, END, Duration.ZERO, VEHICLE_ID));
        Reservation afterRead = reads.loadAggregate(held.code()).orElseThrow();
        assertEquals(ReservationStatus.HELD, afterRead.status());
        assertEquals(held.holdExpiresAt(), afterRead.holdExpiresAt());
        assertEquals(held.statusChangedAt(), afterRead.statusChangedAt());
        assertTrue(writes.updateStatus(held.code(), ReservationStatus.HELD,
                ReservationStatus.RELEASED, CREATED.plusSeconds(3600)));
        assertTrue(busy(START, END, Duration.ZERO, VEHICLE_ID).isEmpty());
    }

    /** Kiểm hai đầu tiếp giáp đều không chồng khi đệm bằng không, đúng cận [). */
    @Test
    void adjacentPeriodsOnBothSidesAreFree() {
        writes.insert(held("KL-BUSY01", VEHICLE_ID, START, END)).orElseThrow();
        assertTrue(busy(START.minusSeconds(3600), START, Duration.ZERO, VEHICLE_ID).isEmpty());
        assertTrue(busy(END, END.plusSeconds(3600), Duration.ZERO, VEHICLE_ID).isEmpty());
    }

    /** Kiểm chồng trái, chồng phải, bao ngoài, nằm trong và trùng toàn bộ đều tính bận. */
    @ParameterizedTest
    @CsvSource({"-3600,3600", "3600,10800", "-3600,10800", "1800,5400", "0,7200"})
    void detectsEveryOverlapShape(long startOffset, long endOffset) {
        writes.insert(held("KL-BUSY01", VEHICLE_ID, START, END)).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), busy(START.plusSeconds(startOffset),
                START.plusSeconds(endOffset), Duration.ZERO, VEHICLE_ID));
    }

    /** Kiểm đệm truy vấn chạm đúng biên thì rảnh, vượt biên mới bận; không cộng đệm hai lần. */
    @Test
    void appliesQueryBufferExactlyOnce() {
        writes.insert(held("KL-BUSY01", VEHICLE_ID, END.plusSeconds(3600),
                END.plusSeconds(7200))).orElseThrow();
        assertTrue(busy(START, END, Duration.ZERO, VEHICLE_ID).isEmpty());
        assertTrue(busy(START, END, Duration.ofHours(1), VEHICLE_ID).isEmpty());
        assertEquals(Set.of(VEHICLE_ID), busy(START, END, Duration.ofHours(2), VEHICLE_ID));
    }

    /** Kiểm COMPLETED giữ đệm đã lưu, nhưng hết khoảng đó thì xe trở lại rảnh. */
    @Test
    void completedStillBlocksStoredBuffer() {
        Reservation completed = held("KL-BUSY01", VEHICLE_ID, START, END.plusSeconds(7200))
                .confirm(CREATED.plusSeconds(1))
                .markInUse(CREATED.plusSeconds(2))
                .complete(CREATED.plusSeconds(3));
        writes.insert(completed).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), busy(END.plusSeconds(3600), END.plusSeconds(5400),
                Duration.ZERO, VEHICLE_ID));
        assertTrue(busy(END.plusSeconds(7200), END.plusSeconds(10800), Duration.ZERO, VEHICLE_ID).isEmpty());
    }

    /** Kiểm compliance vô hạn không chặn trước mốc bắt đầu nhưng chặn tại mốc đó và tương lai xa. */
    @Test
    void unboundedComplianceBlocksOnlyFromItsStart() {
        writes.insert(Reservation.createBlocked("KL-BUSY01", VEHICLE_ID,
                ReservationPeriod.unboundedFrom(START), ReservationKind.COMPLIANCE_HOLD,
                null, CREATED)).orElseThrow();
        assertTrue(busy(START.minusSeconds(3600), START, Duration.ZERO, VEHICLE_ID).isEmpty());
        assertEquals(Set.of(VEHICLE_ID), busy(START, END, Duration.ZERO, VEHICLE_ID));
        assertEquals(Set.of(VEHICLE_ID), busy(START.plus(Duration.ofDays(3650)),
                END.plus(Duration.ofDays(3650)), Duration.ZERO, VEHICLE_ID));
    }

    /** Kiểm chỉ trả ứng viên, loại trùng do nhiều khóa và không trả xe ứng viên chưa có lịch. */
    @Test
    void restrictsCandidatesAndDeduplicatesVehicles() {
        writes.insert(held("KL-BUSY01", VEHICLE_ID, START, END)).orElseThrow();
        writes.insert(held("KL-BUSY02", VEHICLE_ID, END, END.plusSeconds(3600))).orElseThrow();
        writes.insert(held("KL-BUSY03", VEHICLE_ID + 1, START, END)).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), busy(START, END.plusSeconds(3600), Duration.ZERO,
                VEHICLE_ID, VEHICLE_ID, VEHICLE_ID + 2));
        assertTrue(busy(START, END, Duration.ZERO, VEHICLE_ID + 2).isEmpty());
        assertTrue(busy(START, END, Duration.ZERO).isEmpty());
    }

    /**
     * Kiểm hai transaction xen kẽ: đọc rảnh không giữ chỗ; bên khác commit thì hold sau bị từ chối.
     * Mọi thao tác dùng PostgreSQL thật, không nới lỗi timeout thành xung đột nghiệp vụ.
     */
    @Test
    void concurrentCommitInvalidatesPreviouslyFreeResult() throws Exception {
        TestTransaction.end();
        long vehicleId = VEHICLE_ID + 100;
        CountDownLatch readFinished = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var reader = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setTimeout(20);
                return assertThrows(DomainException.class, () -> transaction.executeWithoutResult(status -> {
                    assertTrue(busy(START, END, Duration.ZERO, vehicleId).isEmpty());
                    readFinished.countDown();
                    await(winnerCommitted);
                    holds.hold(HoldReservationCommand.from(vehicleId, START, END,
                            Duration.ZERO, "busy-query-loser"));
                }));
            });
            var writer = executor.submit(() -> {
                await(readFinished);
                var winner = holds.hold(HoldReservationCommand.from(vehicleId, START, END,
                        Duration.ZERO, "busy-query-winner"));
                winnerCommitted.countDown();
                return winner;
            });
            var winner = writer.get(25, TimeUnit.SECONDS);
            DomainException failure = reader.get(25, TimeUnit.SECONDS);
            assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            assertEquals("busy-query-winner", reads.loadAggregate(winner.code()).orElseThrow().bookingCode());
            assertEquals(Set.of(vehicleId), busy(START, END, Duration.ZERO, vehicleId));
        } finally {
            readFinished.countDown();
            winnerCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Workers must terminate.");
        }
    }

    /** Tạo HELD có hạn cũ cố định để truy vấn không thể dựa vào hạn còn hiệu lực. */
    private static Reservation held(String code, long vehicleId, Instant start, Instant end) {
        return Reservation.createHeld(code, vehicleId, ReservationPeriod.finite(start, end),
                "busy-query-booking", Duration.ofHours(1), CREATED);
    }

    /** Gọi use case thật với khoảng chưa cộng đệm và tập ứng viên cho từng ca kiểm. */
    private Set<Long> busy(Instant start, Instant end, Duration buffer, Long... candidates) {
        return queries.listBusyVehicleIds(ListBusyVehiclesQuery.from(start, end, buffer, List.of(candidates)));
    }

    /** Chờ phối hợp có giới hạn; timeout hoặc ngắt luồng phải làm test thất bại. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "Concurrent query coordination timed out.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent query coordination interrupted.", failure);
        }
    }
}
