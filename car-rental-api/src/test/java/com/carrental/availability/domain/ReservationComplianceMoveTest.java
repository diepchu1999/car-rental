package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm chỉ thu hẹp khóa giấy tờ khi gia hạn, không đổi danh tính hoặc trạng thái theo BR-015. */
class ReservationComplianceMoveTest {
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant START = CREATED.plusSeconds(86400);
    private static final Instant NEXT = START.plusSeconds(86400);

    /** Kiểm tạo bản bất biến mới, chỉ đổi cận dưới; mốc đổi trạng thái khác mốc tạo cũng được giữ nguyên. */
    @Test
    void movesOnlyStartAndPreservesIdentityAndTimestamps() {
        Reservation before = Reservation.restore("KL-MOVE01", 42L, ReservationPeriod.unboundedFrom(START),
                ReservationKind.COMPLIANCE_HOLD, ReservationStatus.BLOCKED, null, " Renewed document ",
                null, CREATED, CREATED.plusSeconds(60));
        Reservation after = before.moveComplianceHoldStart(NEXT);
        assertNotSame(before, after);
        assertEquals(START, before.period().startInclusive());
        assertEquals(NEXT, after.period().startInclusive());
        assertNull(after.period().endExclusive());
        assertEquals(before.code(), after.code());
        assertEquals(before.vehicleId(), after.vehicleId());
        assertEquals(before.kind(), after.kind());
        assertEquals(before.status(), after.status());
        assertEquals(before.reason(), after.reason());
        assertEquals(before.bookingCode(), after.bookingCode());
        assertEquals(before.holdExpiresAt(), after.holdExpiresAt());
        assertEquals(before.createdAt(), after.createdAt());
        assertEquals(before.statusChangedAt(), after.statusChangedAt());
    }

    /** Kiểm mốc bằng hoặc lùi bị từ chối, không vô tình mở rộng khóa BR-015. */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonIncreasingStart(long seconds) {
        Reservation before = compliance();
        DomainException failure = assertThrows(DomainException.class,
                () -> before.moveComplianceHoldStart(START.plusSeconds(seconds)));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(START, before.period().startInclusive());
    }

    /** Kiểm không tự chọn mốc khi bên gọi bỏ trống. */
    @Test
    void rejectsMissingStart() {
        DomainException failure = assertThrows(DomainException.class, () -> compliance().moveComplianceHoldStart(null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Kiểm mọi loại khác compliance đều không được dùng thao tác gia hạn giấy tờ. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "COMPLIANCE_HOLD", mode = EnumSource.Mode.EXCLUDE)
    void rejectsOtherKinds(ReservationKind kind) {
        ReservationPeriod period = ReservationPeriod.finite(START, NEXT);
        Reservation before = kind == ReservationKind.RENTAL
                ? Reservation.createHeld("KL-MOVE01", 42L, period, "booking", Duration.ofHours(1), CREATED)
                : Reservation.createBlocked("KL-MOVE01", 42L, period, kind, null, CREATED);
        DomainException failure = assertThrows(DomainException.class, () -> before.moveComplianceHoldStart(NEXT));
        assertEquals(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
        assertEquals(period, before.period());
    }

    /** Tạo khóa giấy tờ hợp lệ và độc lập với đồng hồ máy chạy test. */
    private static Reservation compliance() {
        return Reservation.createBlocked("KL-MOVE01", 42L, ReservationPeriod.unboundedFrom(START),
                ReservationKind.COMPLIANCE_HOLD, "Expired document", CREATED);
    }
}
