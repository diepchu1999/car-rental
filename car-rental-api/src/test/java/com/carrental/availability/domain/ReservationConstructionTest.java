package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm bất biến khởi tạo và khôi phục khóa lịch theo BR-102, BR-103,
 * BR-104, BR-015, BR-225 và status-flow §2, không dùng Spring hoặc CSDL.
 *
 * <p>Mã trong fixture chỉ là dữ liệu test, không quy định mẫu mã nghiệp vụ.
 * Thời gian quá khứ cố định chứng minh domain không tự đọc đồng hồ.
 */
class ReservationConstructionTest {

    private static final String CODE = "reservation-test-1";
    private static final String BOOKING_CODE = "booking-test-1";
    private static final long VEHICLE_ID = 42L;
    private static final String REASON = "  Scheduled inspection  ";
    private static final Instant CREATED = Instant.parse("2000-01-01T08:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2000-01-01T09:00:00Z");
    private static final Instant CHANGED = Instant.parse("2000-01-01T08:30:00Z");
    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(
            Instant.parse("2000-01-02T10:00:00Z"),
            Instant.parse("2000-01-02T14:00:00Z")
    );

    /**
     * Kiểm hạn dùng đúng cấu hình truyền từ application, không đóng cứng một giờ.
     *
     * @param minutes thời hạn cấu hình mô phỏng theo BR-225
     */
    @ParameterizedTest
    @ValueSource(longs = {60, 90})
    void createsHeldUsingConfiguredTtlAndOneCreationTime(long minutes) {
        Reservation result = Reservation.createHeld(
                CODE, VEHICLE_ID, PERIOD, BOOKING_CODE, Duration.ofMinutes(minutes), CREATED
        );

        assertEquals(CODE, result.code());
        assertEquals(VEHICLE_ID, result.vehicleId());
        assertEquals(PERIOD, result.period());
        assertEquals(ReservationKind.RENTAL, result.kind());
        assertEquals(ReservationStatus.HELD, result.status());
        assertEquals(BOOKING_CODE, result.bookingCode());
        assertNull(result.reason());
        assertEquals(CREATED.plusSeconds(minutes * 60), result.holdExpiresAt());
        assertEquals(CREATED, result.createdAt());
        assertEquals(CREATED, result.statusChangedAt());
    }

    /**
     * Kiểm mọi loại khóa vận hành đi thẳng vào BLOCKED, không có hạn giữ chỗ.
     *
     * @param kind loại khóa vận hành theo BR-007, BR-011, BR-012, BR-015, BR-104
     */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void createsOperationalReservationDirectlyBlocked(ReservationKind kind) {
        Reservation result = Reservation.createBlocked(
                CODE, VEHICLE_ID, PERIOD, kind, REASON, CREATED
        );

        assertEquals(CODE, result.code());
        assertEquals(VEHICLE_ID, result.vehicleId());
        assertEquals(PERIOD, result.period());
        assertEquals(kind, result.kind());
        assertEquals(ReservationStatus.BLOCKED, result.status());
        assertNull(result.bookingCode());
        assertNull(result.holdExpiresAt());
        assertEquals(REASON, result.reason());
        assertEquals(CREATED, result.createdAt());
        assertEquals(CREATED, result.statusChangedAt());
    }

    /**
     * Kiểm lý do được giữ nguyên, kể cả null theo DDL, không bị cắt khoảng trắng.
     *
     * @param reason lý do cần lưu
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {REASON})
    void preservesOptionalReason(String reason) {
        Reservation result = Reservation.createBlocked(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.MAINTENANCE, reason, CREATED
        );
        assertEquals(reason, result.reason());
    }

    /** Kiểm COMPLIANCE_HOLD có thể chặn vô hạn về phía trên theo BR-015. */
    @Test
    void createsUnboundedComplianceHold() {
        ReservationPeriod unbounded = ReservationPeriod.unboundedFrom(PERIOD.startInclusive());
        Reservation result = Reservation.createBlocked(
                CODE, VEHICLE_ID, unbounded, ReservationKind.COMPLIANCE_HOLD, REASON, CREATED
        );
        assertEquals(unbounded, result.period());
        assertTrue(result.period().isUnbounded());
        assertEquals(ReservationStatus.BLOCKED, result.status());
    }

    /**
     * Kiểm loại khóa khác không thể dùng khoảng vô hạn, kể cả khi khôi phục.
     *
     * @param kind loại khóa không được phép vô hạn
     */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "COMPLIANCE_HOLD", mode = EnumSource.Mode.EXCLUDE)
    void rejectsUnboundedPeriodForOtherKinds(ReservationKind kind) {
        ReservationPeriod unbounded = ReservationPeriod.unboundedFrom(CREATED);
        boolean rental = kind == ReservationKind.RENTAL;
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, unbounded, kind,
                rental ? ReservationStatus.HELD : ReservationStatus.BLOCKED,
                rental ? BOOKING_CODE : null, null, rental ? EXPIRY : null, CREATED, CREATED
        ), "Only COMPLIANCE_HOLD may have an unbounded period.");

        assertInvalid(() -> {
            if (rental) {
                Reservation.createHeld(CODE, VEHICLE_ID, unbounded, BOOKING_CODE,
                        Duration.ofHours(1), CREATED);
            } else {
                Reservation.createBlocked(CODE, VEHICLE_ID, unbounded, kind, null, CREATED);
            }
        }, "Only COMPLIANCE_HOLD may have an unbounded period.");
    }

    /** Kiểm không thể tạo RENTAL qua đường tạo khóa vận hành. */
    @Test
    void rejectsRentalThroughBlockFactory() {
        assertInvalid(() -> Reservation.createBlocked(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.RENTAL, REASON, CREATED
        ), "RENTAL must be created through the hold workflow.");
    }

    /**
     * Kiểm khôi phục giữ nguyên trạng thái, hạn cũ và khoảng đã cộng đệm.
     *
     * @param status trạng thái hợp lệ của khóa thuê
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "BLOCKED", mode = EnumSource.Mode.EXCLUDE)
    void restoresHistoricalRentalWithoutRecomputingPolicy(ReservationStatus status) {
        Reservation result = Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.RENTAL, status,
                BOOKING_CODE, null, EXPIRY, CREATED, CHANGED
        );
        assertEquals(CODE, result.code());
        assertEquals(VEHICLE_ID, result.vehicleId());
        assertEquals(PERIOD, result.period());
        assertEquals(ReservationKind.RENTAL, result.kind());
        assertEquals(status, result.status());
        assertEquals(BOOKING_CODE, result.bookingCode());
        assertNull(result.reason());
        assertEquals(EXPIRY, result.holdExpiresAt());
        assertEquals(CREATED, result.createdAt());
        assertEquals(CHANGED, result.statusChangedAt());
    }

    /**
     * Kiểm khôi phục cả khóa vận hành đang chặn và đã hoàn tất mà không mất lý do.
     *
     * @param status trạng thái hợp lệ của khóa vận hành
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"BLOCKED", "COMPLETED"})
    void restoresOperationalReservation(ReservationStatus status) {
        Reservation result = Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.TRANSFER, status,
                null, REASON, null, CREATED, CHANGED
        );
        assertEquals(CODE, result.code());
        assertEquals(VEHICLE_ID, result.vehicleId());
        assertEquals(PERIOD, result.period());
        assertEquals(ReservationKind.TRANSFER, result.kind());
        assertEquals(status, result.status());
        assertNull(result.bookingCode());
        assertNull(result.holdExpiresAt());
        assertEquals(REASON, result.reason());
        assertEquals(CREATED, result.createdAt());
        assertEquals(CHANGED, result.statusChangedAt());
    }

    /**
     * Kiểm mã reservation phải có nội dung, không áp mẫu mã chưa được chốt.
     *
     * @param code mã thiếu hoặc trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsMissingReservationCode(String code) {
        assertInvalid(() -> Reservation.createHeld(
                code, VEHICLE_ID, PERIOD, BOOKING_CODE, Duration.ofHours(1), CREATED
        ), code == null ? "code is required." : "code must not be blank.");
    }

    /**
     * Kiểm khóa thuê bắt buộc có mã đơn theo chk_rental_has_code.
     *
     * @param bookingCode mã đơn thiếu hoặc trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    void rejectsMissingBookingCode(String bookingCode) {
        assertInvalid(() -> Reservation.createHeld(
                CODE, VEHICLE_ID, PERIOD, bookingCode, Duration.ofHours(1), CREATED
        ), bookingCode == null ? "bookingCode is required." : "bookingCode must not be blank.");
    }

    /**
     * Kiểm định danh xe phải dương dù availability không tra cứu xe tồn tại.
     *
     * @param vehicleId định danh không hợp lệ
     */
    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveVehicleId(long vehicleId) {
        assertInvalid(() -> Reservation.createHeld(
                CODE, vehicleId, PERIOD, BOOKING_CODE, Duration.ofHours(1), CREATED
        ), "vehicleId must be greater than zero.");
    }

    /**
     * Kiểm từng trường cấu trúc bắt buộc trên đường khôi phục dữ liệu.
     *
     * @param field trường được bỏ trống có chủ đích
     */
    @ParameterizedTest
    @ValueSource(strings = {"period", "kind", "status", "createdAt", "statusChangedAt", "holdExpiresAt"})
    void rejectsMissingRequiredStoredField(String field) {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID,
                field.equals("period") ? null : PERIOD,
                field.equals("kind") ? null : ReservationKind.RENTAL,
                field.equals("status") ? null : ReservationStatus.HELD,
                BOOKING_CODE, null,
                field.equals("holdExpiresAt") ? null : EXPIRY,
                field.equals("createdAt") ? null : CREATED,
                field.equals("statusChangedAt") ? null : CREATED
        ), field + " is required.");
    }

    /**
     * Kiểm cấu hình thời hạn thiếu, bằng không hoặc âm không tạo được HELD.
     *
     * @param ttl thời hạn không hợp lệ
     */
    @ParameterizedTest
    @MethodSource("invalidTtls")
    void rejectsInvalidTtl(Duration ttl) {
        assertInvalid(() -> Reservation.createHeld(
                CODE, VEHICLE_ID, PERIOD, BOOKING_CODE, ttl, CREATED
        ), ttl == null ? "holdTtl is required." : "holdTtl must be greater than zero.");
    }

    /** Kiểm factory giữ chỗ không tự lấy giờ hiện tại khi thiếu mốc tạo. */
    @Test
    void heldFactoryRejectsMissingCreationTime() {
        assertInvalid(() -> Reservation.createHeld(
                CODE, VEHICLE_ID, PERIOD, BOOKING_CODE, Duration.ofHours(1), null
        ), "createdAt is required.");
    }

    /** Kiểm factory khóa vận hành không mặc định loại khóa khi bị thiếu. */
    @Test
    void blockFactoryRejectsMissingKind() {
        assertInvalid(() -> Reservation.createBlocked(
                CODE, VEHICLE_ID, PERIOD, null, REASON, CREATED
        ), "kind is required.");
    }

    /**
     * Kiểm cả vượt miền Instant lẫn tràn số học khi tính hạn.
     *
     * @param creationTime thời điểm tạo
     * @param ttl thời hạn gây tràn
     */
    @ParameterizedTest
    @MethodSource("overflowingExpiries")
    void rejectsExpiryOverflow(Instant creationTime, Duration ttl) {
        assertInvalid(() -> Reservation.createHeld(
                CODE, VEHICLE_ID, PERIOD, BOOKING_CODE, ttl, creationTime
        ), "Hold expiry exceeds the supported time range.");
    }

    /**
     * Kiểm hạn bằng hoặc trước lúc tạo bị từ chối khi khôi phục HELD.
     *
     * @param offsetSeconds độ lệch hạn so với lúc tạo
     */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsExpiryNotAfterCreation(long offsetSeconds) {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.RENTAL, ReservationStatus.HELD,
                BOOKING_CODE, null, CREATED.plusSeconds(offsetSeconds), CREATED, CREATED
        ), "holdExpiresAt must be after createdAt.");
    }

    /** Kiểm dữ liệu RENTAL/BLOCKED không lọt qua đường khôi phục. */
    @Test
    void rejectsBlockedRentalOnRestore() {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.RENTAL, ReservationStatus.BLOCKED,
                BOOKING_CODE, null, EXPIRY, CREATED, CREATED
        ), "A rental reservation must not have BLOCKED status.");
    }

    /**
     * Kiểm khóa vận hành không mang các trạng thái chỉ thuộc nhánh thuê xe.
     *
     * @param status trạng thái không hợp lệ với khóa vận hành
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"BLOCKED", "COMPLETED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsRentalStatusesOnOperationalReservation(ReservationStatus status) {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.MAINTENANCE, status,
                null, REASON, null, CREATED, CREATED
        ), "An operational reservation must have BLOCKED or COMPLETED status.");
    }

    /** Kiểm khóa vận hành không bị gán mã đơn thuê trên đường khôi phục. */
    @Test
    void rejectsBookingCodeOnOperationalReservation() {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.MAINTENANCE, ReservationStatus.BLOCKED,
                BOOKING_CODE, REASON, null, CREATED, CREATED
        ), "An operational reservation must not have a bookingCode.");
    }

    /** Kiểm khóa vận hành không bị gán hạn giữ chỗ như khóa thuê xe. */
    @Test
    void rejectsHoldExpiryOnOperationalReservation() {
        assertInvalid(() -> Reservation.restore(
                CODE, VEHICLE_ID, PERIOD, ReservationKind.MAINTENANCE, ReservationStatus.BLOCKED,
                null, REASON, EXPIRY, CREATED, CREATED
        ), "An operational reservation must not have a hold expiry.");
    }

    /**
     * Cung cấp thời hạn sai, gồm giá trị âm nhỏ hơn một giây.
     *
     * @return các thời hạn cần từ chối
     */
    private static Stream<Arguments> invalidTtls() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofHours(-1)), Arguments.of(Duration.ofNanos(-1)));
    }

    /**
     * Cung cấp dữ liệu vượt miền thời gian và vượt miền long.
     *
     * @return các phép tính hạn gây tràn
     */
    private static Stream<Arguments> overflowingExpiries() {
        return Stream.of(Arguments.of(Instant.MAX, Duration.ofNanos(1)),
                Arguments.of(CREATED, Duration.ofSeconds(Long.MAX_VALUE)));
    }

    /**
     * Kiểm lỗi đầu vào đúng nguyên nhân, không chấp nhận exception bất kỳ.
     *
     * @param action thao tác cần từ chối
     * @param message thông báo mong đợi
     */
    private static void assertInvalid(Executable action, String message) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals(message, failure.getMessage());
    }
}
