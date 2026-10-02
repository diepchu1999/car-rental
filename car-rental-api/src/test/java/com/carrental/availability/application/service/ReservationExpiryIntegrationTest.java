package com.carrental.availability.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import com.carrental.availability.application.port.in.HoldReservationUseCase;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Kiểm dọn hết hạn qua use case, transaction và PostgreSQL thật theo BR-103. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT77M")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ReservationExpiryIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(7200);
    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(
            NOW.plusSeconds(86400), NOW.plusSeconds(93600));
    private static final long VEHICLE_ID = 9_000_000_007_001L;

    @Autowired
    private ExpireReservationHoldsUseCase expiry;
    @Autowired
    private HoldReservationUseCase holds;
    @Autowired
    private WriteReservationPort writes;
    @Autowired
    private ReadReservationPort reads;
    @Autowired
    private ApplicationContext context;
    @MockitoBean(name = "applicationClock")
    private Clock clock;

    /** Cố định mốc dọn; TTL cấu hình cố tình khác hạn trên bản ghi được chuẩn bị. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
    }

    /** Kiểm cấu hình test thật đã tắt job nền, không để fixture bị sửa bất chợt trong suite. */
    @Test
    void backgroundSchedulerIsDisabledInDatabaseTests() {
        assertEquals("false", context.getEnvironment().getProperty("car-rental.availability.hold-sweep-enabled"));
        assertFalse(context.containsBean("reservationHoldExpiryScheduler"));
    }

    /** Kiểm hạn trước, đúng và sau mốc dọn một microsecond; chỉ hai bản đầu được nhả. */
    @Test
    void expiresAtInclusiveCutoffWithoutChangingOtherFields() {
        Reservation before = held("KL-EXP001", VEHICLE_ID, CREATED, NOW.minusNanos(1000));
        Reservation at = held("KL-EXP002", VEHICLE_ID + 1, CREATED, NOW);
        Reservation after = held("KL-EXP003", VEHICLE_ID + 2, CREATED, NOW.plusNanos(1000));
        for (Reservation reservation : List.of(before, at, after)) {
            writes.insert(reservation).orElseThrow();
        }
        assertEquals(2, expiry.expireHolds());
        assertStored(before, ReservationStatus.RELEASED, NOW);
        assertStored(at, ReservationStatus.RELEASED, NOW);
        assertStored(after, ReservationStatus.HELD, CREATED);
    }

    /** Kiểm chỉ HELD được nhả, kể cả trạng thái khác vẫn có hold_expires_at cũ đã quá hạn. */
    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void onlyChangesHeldStatus(ReservationStatus status) {
        ReservationKind kind = status == ReservationStatus.BLOCKED
                ? ReservationKind.MAINTENANCE : ReservationKind.RENTAL;
        Reservation original = Reservation.restore("KL-EXP001", VEHICLE_ID, PERIOD, kind, status,
                kind == ReservationKind.RENTAL ? "expiry-booking" : null,
                "Preserve this reason", kind == ReservationKind.RENTAL ? NOW.minusSeconds(1) : null,
                CREATED, CREATED);
        writes.insert(original).orElseThrow();
        boolean expires = status == ReservationStatus.HELD;
        assertEquals(expires ? 1 : 0, expiry.expireHolds());
        assertStored(original, expires ? ReservationStatus.RELEASED : status, expires ? NOW : CREATED);
    }

    /** Kiểm dùng hạn đã lưu, không lấy TTL hiện tại cộng lại vào thời điểm tạo. */
    @Test
    void usesFrozenExpiryInsteadOfCurrentPolicy() {
        Reservation expired = held("KL-EXP001", VEHICLE_ID, NOW.minusSeconds(1800), NOW.minusSeconds(900));
        Reservation alive = held("KL-EXP002", VEHICLE_ID + 1, NOW.minusSeconds(4200), NOW.plusSeconds(1200));
        writes.insert(expired).orElseThrow();
        writes.insert(alive).orElseThrow();
        assertEquals(1, expiry.expireHolds());
        assertStored(expired, ReservationStatus.RELEASED, NOW);
        assertStored(alive, ReservationStatus.HELD, alive.createdAt());
    }

    /** Kiểm lượt lặp lại trả không và không ghi đè status_changed_at của hồ sơ đã nhả. */
    @Test
    void repeatedSweepDoesNotRewriteReleasedHistory() {
        Reservation original = held("KL-EXP001", VEHICLE_ID, CREATED, NOW);
        writes.insert(original).orElseThrow();
        assertEquals(1, expiry.expireHolds());
        when(clock.instant()).thenReturn(NOW.plusSeconds(30));
        assertEquals(0, expiry.expireHolds());
        assertStored(original, ReservationStatus.RELEASED, NOW);
    }

    /** Kiểm quá hạn còn báo bận, sau dọn thì hold lại đúng xe và khoảng được CSDL chấp nhận. */
    @Test
    void sweepMakesVehicleAvailableForAnotherHold() {
        Reservation original = held("KL-EXP001", VEHICLE_ID, CREATED, NOW);
        writes.insert(original).orElseThrow();
        assertEquals(Set.of(VEHICLE_ID), reads.findBusyVehicleIds(PERIOD, List.of(VEHICLE_ID)));
        assertEquals(1, expiry.expireHolds());
        assertTrue(reads.findBusyVehicleIds(PERIOD, List.of(VEHICLE_ID)).isEmpty());
        var replacement = holds.hold(HoldReservationCommand.from(VEHICLE_ID,
                PERIOD.startInclusive(), PERIOD.endExclusive(), Duration.ZERO, "replacement-booking"));
        assertNotEquals(original.code(), replacement.code());
        assertEquals(ReservationStatus.HELD, reads.loadAggregate(replacement.code()).orElseThrow().status());
        assertStored(original, ReservationStatus.RELEASED, NOW);
    }

    /** Kiểm rollback transaction bên gọi khôi phục bản HELD đã được job cập nhật. */
    @Test
    void sweepRollsBackWithCaller() {
        Reservation original = held("KL-EXPRBK", VEHICLE_ID + 99, CREATED, NOW);
        writes.insert(original).orElseThrow();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        assertEquals(1, expiry.expireHolds());
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
        assertStored(original, ReservationStatus.HELD, CREATED);
        // Dọn dữ liệu đã commit để các test sau không bị tác động bởi fixture này.
        assertEquals(1, expiry.expireHolds());
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    /** Tạo HELD với hạn tùy fixture để kiểm không phụ thuộc cấu hình TTL hiện tại. */
    private static Reservation held(String code, long vehicleId, Instant createdAt, Instant expiresAt) {
        return Reservation.createHeld(code, vehicleId, PERIOD, "expiry-booking",
                Duration.between(createdAt, expiresAt), createdAt);
    }

    /** Đối chiếu mọi thuộc tính để chứng minh chỉ trạng thái và mốc thay đổi được cập nhật. */
    private void assertStored(Reservation original, ReservationStatus status, Instant changedAt) {
        Reservation actual = reads.loadAggregate(original.code()).orElseThrow();
        assertEquals(status, actual.status());
        assertEquals(changedAt, actual.statusChangedAt());
        assertEquals(original.code(), actual.code());
        assertEquals(original.vehicleId(), actual.vehicleId());
        assertEquals(original.period(), actual.period());
        assertEquals(original.kind(), actual.kind());
        assertEquals(original.bookingCode(), actual.bookingCode());
        assertEquals(original.reason(), actual.reason());
        assertEquals(original.holdExpiresAt(), actual.holdExpiresAt());
        assertEquals(original.createdAt(), actual.createdAt());
    }
}
