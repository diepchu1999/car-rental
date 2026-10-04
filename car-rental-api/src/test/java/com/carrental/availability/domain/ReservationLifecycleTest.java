package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm toàn bộ cạnh và những bước chuyển bị cấm của reservation theo
 * status-flow §2, BR-103, BR-304; bảo toàn đệm theo BR-109, BR-116.
 *
 * <p>Test thuần Java chỉ chứng minh hành vi domain, không thay thế
 * kiểm chứng transaction và chống chồng lịch bằng PostgreSQL thật.
 */
class ReservationLifecycleTest {

    private static final Instant CREATED = Instant.parse("2026-09-30T08:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-09-30T09:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-30T08:30:00Z");
    private static final Instant PICKUP = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant RETURNED = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant BUFFER_END = Instant.parse("2026-09-30T14:00:00Z");
    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(PICKUP, BUFFER_END);

    /** Kiểm HELD vẫn xác nhận được ngay trước hạn, kể cả cách một nano giây. */
    @Test
    void confirmsImmediatelyBeforeExpiry() {
        Reservation before = reservationAt(ReservationStatus.HELD);
        Instant time = EXPIRY.minusNanos(1);

        Reservation after = before.confirm(time);

        assertTransition(before, after, ReservationStatus.HELD, ReservationStatus.CONFIRMED, time);
    }

    /**
     * Kiểm tại đúng hạn và sau hạn đều báo HOLD_EXPIRED, không đổi trạng thái.
     *
     * @param offsetSeconds độ lệch so với hạn giữ chỗ
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void rejectsConfirmationAtOrAfterExpiry(long offsetSeconds) {
        Reservation reservation = reservationAt(ReservationStatus.HELD);
        assertRuleViolation(() -> reservation.confirm(EXPIRY.plusSeconds(offsetSeconds)),
                ErrorCode.HOLD_EXPIRED);
        assertEquals(ReservationStatus.HELD, reservation.status());
        assertEquals(CREATED, reservation.statusChangedAt());
    }

    /**
     * Kiểm chỉ HELD được xác nhận; trạng thái cuối không được mở lại.
     *
     * @param status trạng thái nguồn bị cấm
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "HELD", mode = EnumSource.Mode.EXCLUDE)
    void rejectsConfirmationFromOtherStatuses(ReservationStatus status) {
        Reservation reservation = reservationAt(status);
        assertInvalidTransition(() -> reservation.confirm(CONFIRMED_AT));
        assertEquals(status, reservation.status());
    }

    /**
     * Kiểm khách có thể nhả HELD trước hạn hoặc nhả CONFIRMED theo BR-304.
     *
     * @param status trạng thái nguồn được phép nhả
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "CONFIRMED"})
    void releasesHeldOrConfirmedReservation(ReservationStatus status) {
        Reservation before = reservationAt(status);
        Reservation after = before.release(CONFIRMED_AT);
        assertTransition(before, after, status, ReservationStatus.RELEASED, CONFIRMED_AT);
    }

    /** Kiểm chỗ giữ quá hạn vẫn nhả được, không bị logic hạn cản việc dọn. */
    @Test
    void releasesExpiredHoldWithoutRecomputingExpiry() {
        Reservation before = reservationAt(ReservationStatus.HELD);
        Instant time = EXPIRY.plusSeconds(30);
        Reservation after = before.release(time);
        assertTransition(before, after, ReservationStatus.HELD, ReservationStatus.RELEASED, time);
        assertEquals(EXPIRY, after.holdExpiresAt());
    }

    /**
     * Kiểm không thể nhả khóa vận hành, chuyến đang chạy hoặc trạng thái cuối.
     *
     * @param status trạng thái nguồn bị cấm
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"HELD", "CONFIRMED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsReleaseFromOtherStatuses(ReservationStatus status) {
        Reservation reservation = reservationAt(status);
        assertInvalidTransition(() -> reservation.release(RETURNED));
        assertEquals(status, reservation.status());
    }

    /** Kiểm CONFIRMED được bàn giao kể cả khi hạn giữ chỗ ban đầu đã qua. */
    @Test
    void marksConfirmedReservationInUseWithoutRecheckingHoldExpiry() {
        Reservation before = reservationAt(ReservationStatus.CONFIRMED);
        Reservation after = before.markInUse(PICKUP);
        assertTransition(before, after, ReservationStatus.CONFIRMED, ReservationStatus.IN_USE, PICKUP);
    }

    /**
     * Kiểm không thể bỏ qua xác nhận hoặc mở lại trạng thái cuối để bàn giao.
     *
     * @param status trạng thái nguồn bị cấm
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsMarkInUseFromOtherStatuses(ReservationStatus status) {
        Reservation reservation = reservationAt(status);
        assertInvalidTransition(() -> reservation.markInUse(PICKUP));
        assertEquals(status, reservation.status());
    }

    /**
     * Kiểm hoàn tất giữ nguyên khoảng tới 14 giờ dù công việc kết thúc lúc 12 giờ.
     *
     * @param status trạng thái nguồn được phép hoàn tất
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"IN_USE", "BLOCKED"})
    void completesWithoutShorteningStoredPeriod(ReservationStatus status) {
        Reservation before = reservationAt(status);
        Reservation after = before.complete(RETURNED);
        assertTransition(before, after, status, ReservationStatus.COMPLETED, RETURNED);
        assertEquals(PICKUP, after.period().startInclusive());
        assertEquals(BUFFER_END, after.period().endExclusive());
    }

    /** Kiểm cả bốn loại vận hành hữu hạn vẫn hoàn tất và giữ nguyên khoảng theo BR-012. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = {"RENTAL", "COMPLIANCE_HOLD"}, mode = EnumSource.Mode.EXCLUDE)
    void completesFiniteOperationalKinds(ReservationKind kind) {
        Reservation before = Reservation.createBlocked("reservation-test-1", 42L, PERIOD,
                kind, "Scheduled work", CREATED);
        Reservation after = before.complete(RETURNED);
        assertTransition(before, after, ReservationStatus.BLOCKED, ReservationStatus.COMPLETED, RETURNED);
    }

    /** Kiểm BR-015: mọi thao tác chuyển trạng thái đều bị từ chối, khóa giấy tờ vẫn nguyên vẹn. */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void rejectsEveryStatusTransitionForComplianceHold(String operation) {
        ReservationPeriod period = ReservationPeriod.unboundedFrom(PICKUP);
        Reservation before = Reservation.createBlocked("reservation-test-1", 42L, period,
                ReservationKind.COMPLIANCE_HOLD, "Expired document", CREATED);
        assertInvalidTransition(() -> applyOperation(before, operation, RETURNED));
        assertEquals(ReservationStatus.BLOCKED, before.status());
        assertEquals(CREATED, before.statusChangedAt());
        assertEquals(period, before.period());
    }

    /**
     * Kiểm không hoàn tất từ trạng thái chưa bàn giao hoặc từ trạng thái cuối.
     *
     * @param status trạng thái nguồn bị cấm
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"IN_USE", "BLOCKED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsCompletionFromOtherStatuses(ReservationStatus status) {
        Reservation reservation = reservationAt(status);
        assertInvalidTransition(() -> reservation.complete(RETURNED));
        assertEquals(status, reservation.status());
    }

    /**
     * Kiểm từng thao tác đòi mốc thời gian do application truyền vào.
     *
     * @param operation tên thao tác
     * @param source trạng thái hợp lệ cho thao tác để lỗi chỉ do thiếu thời gian
     */
    @ParameterizedTest
    @CsvSource({"confirm, HELD", "release, HELD", "markInUse, CONFIRMED", "complete, IN_USE"})
    void rejectsMissingTransitionTime(String operation, ReservationStatus source) {
        Reservation reservation = reservationAt(source);
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> applyOperation(reservation, operation, null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        assertEquals("changedAt is required.", failure.getMessage());
        assertEquals(source, reservation.status());
        assertEquals(CREATED, reservation.statusChangedAt());
    }

    /**
     * Kiểm luồng thật từ tạo HELD tới COMPLETED, giữ nguyên các bản trung gian,
     * hạn ban đầu, mã reservation khác mã đơn và khoảng đệm hai giờ.
     */
    @Test
    void followsFullRentalLifecycleWithoutMutatingEarlierVersions() {
        Reservation held = Reservation.createHeld("reservation-test-1", 42L,
                PERIOD, "booking-test-1", Duration.ofHours(1), CREATED);
        Reservation confirmed = held.confirm(CONFIRMED_AT);
        Reservation inUse = confirmed.markInUse(PICKUP);
        Reservation completed = inUse.complete(RETURNED);

        assertEquals(ReservationStatus.HELD, held.status());
        assertEquals(CREATED, held.statusChangedAt());
        assertEquals(ReservationStatus.CONFIRMED, confirmed.status());
        assertEquals(CONFIRMED_AT, confirmed.statusChangedAt());
        assertEquals(ReservationStatus.IN_USE, inUse.status());
        assertEquals(PICKUP, inUse.statusChangedAt());
        assertEquals(ReservationStatus.COMPLETED, completed.status());
        assertEquals(RETURNED, completed.statusChangedAt());
        assertEquals("reservation-test-1", completed.code());
        assertEquals("booking-test-1", completed.bookingCode());
        assertEquals(EXPIRY, completed.holdExpiresAt());
        assertEquals(CREATED, completed.createdAt());
        assertEquals(PERIOD, completed.period());
    }

    /**
     * Kiểm khóa vận hành hoàn tất cũng là trạng thái cuối, không chỉ khóa thuê.
     *
     * @param operation thao tác không được thực hiện sau khi hoàn tất
     */
    @ParameterizedTest
    @ValueSource(strings = {"confirm", "release", "markInUse", "complete"})
    void rejectsEveryOperationOnCompletedOperationalReservation(String operation) {
        Reservation completed = reservationAt(ReservationStatus.BLOCKED).complete(RETURNED);
        assertInvalidTransition(() -> applyOperation(completed, operation, RETURNED.plusSeconds(1)));
        assertEquals(ReservationStatus.COMPLETED, completed.status());
        assertEquals(RETURNED, completed.statusChangedAt());
        assertEquals(PERIOD, completed.period());
    }

    /**
     * Khôi phục fixture đúng loại khóa để test sai cạnh không thất bại lúc dựng dữ liệu.
     *
     * @param status trạng thái cần kiểm
     * @return khóa thuê hoặc khóa bảo dưỡng ở trạng thái yêu cầu
     */
    private static Reservation reservationAt(ReservationStatus status) {
        boolean operational = status == ReservationStatus.BLOCKED;
        return Reservation.restore("reservation-test-1", 42L, PERIOD,
                operational ? ReservationKind.MAINTENANCE : ReservationKind.RENTAL,
                status, operational ? null : "booking-test-1",
                operational ? "  Scheduled maintenance  " : null,
                operational ? null : EXPIRY, CREATED, CREATED);
    }

    /**
     * Gọi thao tác cần kiểm với thời điểm tường minh.
     *
     * @param reservation fixture cần thao tác
     * @param operation tên thao tác từ dữ liệu test
     * @param time thời điểm, có thể null để kiểm validation
     * @return bản aggregate sau thao tác
     */
    private static Reservation applyOperation(Reservation reservation, String operation, Instant time) {
        return switch (operation) {
            case "confirm" -> reservation.confirm(time);
            case "release" -> reservation.release(time);
            case "markInUse" -> reservation.markInUse(time);
            case "complete" -> reservation.complete(time);
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        };
    }

    /**
     * Kiểm chỉ trạng thái và mốc đổi trạng thái thay đổi, bản nguồn vẫn nguyên.
     *
     * @param before bản nguồn có mốc trạng thái CREATED
     * @param after bản kết quả
     * @param source trạng thái nguồn mong đợi
     * @param target trạng thái đích mong đợi
     * @param time thời điểm thao tác
     */
    private static void assertTransition(Reservation before, Reservation after,
                                         ReservationStatus source, ReservationStatus target, Instant time) {
        assertNotSame(before, after);
        assertEquals(source, before.status());
        assertEquals(CREATED, before.statusChangedAt());
        assertEquals(target, after.status());
        assertEquals(time, after.statusChangedAt());
        assertEquals(before.code(), after.code());
        assertEquals(before.vehicleId(), after.vehicleId());
        assertEquals(before.period(), after.period());
        assertEquals(before.kind(), after.kind());
        assertEquals(before.bookingCode(), after.bookingCode());
        assertEquals(before.reason(), after.reason());
        assertEquals(before.holdExpiresAt(), after.holdExpiresAt());
        assertEquals(before.createdAt(), after.createdAt());
    }

    /**
     * Kiểm lỗi chuyển trạng thái, không chấp nhận nhầm thành lỗi hết hạn.
     *
     * @param action thao tác dự kiến bị từ chối
     */
    private static void assertInvalidTransition(Executable action) {
        assertRuleViolation(action, ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION);
    }

    /**
     * Kiểm đủ kiểu exception, mã lỗi, nhóm lỗi và thông báo công khai.
     *
     * @param action thao tác dự kiến bị từ chối
     * @param code mã lỗi mong đợi
     */
    private static void assertRuleViolation(Executable action, ErrorCode code) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(code, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
        assertEquals(code.defaultMessage(), failure.getMessage());
    }
}
