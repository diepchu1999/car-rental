package com.carrental.availability.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/**
 * Biểu diễn một khóa lịch xe và bảo vệ máy trạng thái của khóa.
 *
 * <p>Giữ chỗ theo BR-102, BR-103; khóa vận hành theo BR-007,
 * BR-011, BR-012, BR-015; giải phóng chỗ theo BR-304.
 * Các bước chuyển tuân theo status-flow §2.
 *
 * <p>Đối tượng bất biến. Mỗi thao tác chuyển trạng thái trả về
 * một đối tượng mới và giữ nguyên dữ liệu lịch đã lưu.
 *
 * <p>Khoảng của đơn thuê phải được application cộng đệm
 * trước khi tạo aggregate, theo BR-109 và BR-116.
 * Khôi phục hoặc chuyển trạng thái không cộng đệm lần nữa.
 *
 * <p>Domain không truy cập database, không đọc Clock hoặc cấu hình.
 * Application cung cấp thời điểm và thời hạn cấu hình.
 * PostgreSQL bảo đảm chống chồng lịch theo BR-104, ADR-0005.
 *
 * <p>Mã reservation là định danh nghiệp vụ. Khóa chính số
 * do database sinh không nằm trong aggregate.
 */
public final class Reservation {

    private final String code;
    private final long vehicleId;
    private final ReservationPeriod period;
    private final ReservationKind kind;
    private final ReservationStatus status;
    private final String bookingCode;
    private final String reason;
    private final Instant holdExpiresAt;
    private final Instant createdAt;
    private final Instant statusChangedAt;

    /**
     * Khởi tạo aggregate và kiểm tra các bất biến cấu trúc.
     *
     * <p>Không kiểm thời hạn giữ chỗ so với thời điểm hiện tại,
     * vì phải khôi phục được cả bản ghi đã hết hạn.
     *
     * <p>Không tự đặt mẫu mã reservation, không chuẩn hóa mã
     * hoặc thay đổi nội dung lý do được cung cấp.
     *
     * @param code mã reservation có nội dung
     * @param vehicleId định danh xe lớn hơn không
     * @param period khoảng khóa đã bao gồm đệm nếu là đơn thuê
     * @param kind nguyên nhân khóa
     * @param status trạng thái cần biểu diễn
     * @param bookingCode mã đơn thuê, bắt buộc với RENTAL
     * @param reason lý do đã lưu, có thể null
     * @param holdExpiresAt thời điểm hết hạn, bắt buộc với HELD
     * @param createdAt thời điểm tạo
     * @param statusChangedAt thời điểm đổi trạng thái gần nhất
     * @throws DomainException nếu dữ liệu vi phạm bất biến
     */
    private Reservation(
            String code,
            long vehicleId,
            ReservationPeriod period,
            ReservationKind kind,
            ReservationStatus status,
            String bookingCode,
            String reason,
            Instant holdExpiresAt,
            Instant createdAt,
            Instant statusChangedAt
    ) {
        this.code = Validations.requiredText(code, "code");

        if (vehicleId <= 0L) {
            throw DomainException.invalidInput(
                    "vehicleId must be greater than zero."
            );
        }

        this.vehicleId = vehicleId;
        this.period = Validations.required(period, "period");
        this.kind = Validations.required(kind, "kind");
        this.status = Validations.required(status, "status");
        this.createdAt = Validations.required(createdAt, "createdAt");
        this.statusChangedAt = Validations.required(
                statusChangedAt,
                "statusChangedAt"
        );

        if (this.period.isUnbounded()
                && this.kind != ReservationKind.COMPLIANCE_HOLD) {
            throw DomainException.invalidInput(
                    "Only COMPLIANCE_HOLD may have an unbounded period."
            );
        }

        if (this.kind == ReservationKind.COMPLIANCE_HOLD) {
            if (!this.period.isUnbounded()) {
                throw DomainException.invalidInput(
                        "COMPLIANCE_HOLD must have an unbounded period."
                );
            }
            if (this.status != ReservationStatus.BLOCKED) {
                throw DomainException.invalidInput(
                        "COMPLIANCE_HOLD must have BLOCKED status."
                );
            }
        }

        if (this.kind == ReservationKind.RENTAL) {
            this.bookingCode = Validations.requiredText(
                    bookingCode,
                    "bookingCode"
            );

            if (this.status == ReservationStatus.BLOCKED) {
                throw DomainException.invalidInput(
                        "A rental reservation must not have BLOCKED status."
                );
            }
        } else {
            if (this.status != ReservationStatus.BLOCKED
                    && this.status != ReservationStatus.COMPLETED) {
                throw DomainException.invalidInput(
                        "An operational reservation must have BLOCKED or COMPLETED status."
                );
            }

            if (bookingCode != null) {
                throw DomainException.invalidInput(
                        "An operational reservation must not have a bookingCode."
                );
            }

            if (holdExpiresAt != null) {
                throw DomainException.invalidInput(
                        "An operational reservation must not have a hold expiry."
                );
            }

            this.bookingCode = null;
        }

        if (this.status == ReservationStatus.HELD) {
            Validations.required(holdExpiresAt, "holdExpiresAt");
        }

        if (holdExpiresAt != null
                && !holdExpiresAt.isAfter(this.createdAt)) {
            throw DomainException.invalidInput(
                    "holdExpiresAt must be after createdAt."
            );
        }

        this.reason = reason;
        this.holdExpiresAt = holdExpiresAt;
    }

    /**
     * Tạo chỗ giữ mới ở trạng thái RENTAL/HELD theo BR-102, BR-103.
     *
     * <p>Application lấy holdTtl từ cấu hình hệ thống theo BR-225,
     * không lấy từ đối số của AvailabilityDirectory.
     * Domain tính hạn từ chính mốc createdAt được cung cấp.
     *
     * <p>period là khoảng đã được application cộng đệm.
     * Phương thức không cộng lại và không tự chọn độ dài đệm.
     *
     * @param code mã reservation do application sinh
     * @param vehicleId định danh xe
     * @param period khoảng khóa đã bao gồm đệm
     * @param bookingCode mã đơn thuê
     * @param holdTtl thời hạn giữ chỗ lấy từ cấu hình, phải dương
     * @param createdAt mốc tạo lấy từ Clock chung
     * @return chỗ giữ mới với hai mốc tạo và đổi trạng thái bằng nhau
     * @throws DomainException nếu đầu vào không hợp lệ
     *                         hoặc phép tính hạn vượt giới hạn thời gian
     */
    public static Reservation createHeld(
            String code,
            long vehicleId,
            ReservationPeriod period,
            String bookingCode,
            Duration holdTtl,
            Instant createdAt
    ) {
        Duration checkedTtl = Validations.required(holdTtl, "holdTtl");
        Instant creationTime = Validations.required(createdAt, "createdAt");

        if (checkedTtl.isZero() || checkedTtl.isNegative()) {
            throw DomainException.invalidInput(
                    "holdTtl must be greater than zero."
            );
        }

        Instant expiry;
        try {
            expiry = creationTime.plus(checkedTtl);
        } catch (DateTimeException | ArithmeticException exception) {
            throw DomainException.invalidInput(
                    "Hold expiry exceeds the supported time range."
            );
        }

        return new Reservation(
                code,
                vehicleId,
                period,
                ReservationKind.RENTAL,
                ReservationStatus.HELD,
                bookingCode,
                null,
                expiry,
                creationTime,
                creationTime
        );
    }

    /**
     * Tạo khóa vận hành trực tiếp ở trạng thái BLOCKED.
     *
     * <p>Áp dụng BR-007, BR-011, BR-012, BR-015 và các nguyên nhân
     * bận được BR-104 đưa vào chung bảng khóa lịch.
     * Không cho dùng đường này để tạo khóa RENTAL.
     * COMPLIANCE_HOLD bắt buộc không chặn trên và luôn BLOCKED theo BR-015.
     *
     * <p>Lý do được giữ nguyên để persistence lưu lại.
     * Cho phép null theo hợp đồng cột reason trong DDL đã duyệt.
     *
     * @param code mã reservation do application sinh
     * @param vehicleId định danh xe
     * @param period khoảng khóa vận hành
     * @param kind loại khóa không phải RENTAL
     * @param reason lý do khóa, có thể null
     * @param createdAt mốc tạo lấy từ Clock chung
     * @return khóa vận hành mới, không có mã đơn hoặc hạn giữ chỗ
     * @throws DomainException nếu đầu vào không hợp lệ
     */
    public static Reservation createBlocked(
            String code,
            long vehicleId,
            ReservationPeriod period,
            ReservationKind kind,
            String reason,
            Instant createdAt
    ) {
        ReservationKind checkedKind = Validations.required(kind, "kind");

        if (checkedKind == ReservationKind.RENTAL) {
            throw DomainException.invalidInput(
                    "RENTAL must be created through the hold workflow."
            );
        }

        return new Reservation(
                code,
                vehicleId,
                period,
                checkedKind,
                ReservationStatus.BLOCKED,
                null,
                reason,
                null,
                createdAt,
                createdAt
        );
    }

    /**
     * Khôi phục aggregate từ dữ liệu đã lưu.
     *
     * <p>Không đọc Clock, không tính lại hạn theo cấu hình hiện tại,
     * không cộng thêm đệm và không tự chuyển trạng thái.
     * HELD đã hết hạn vẫn phải đọc được để xử lý đúng BR-103.
     *
     * @param code mã reservation đã lưu
     * @param vehicleId định danh xe đã lưu
     * @param period khoảng khóa đã lưu
     * @param kind loại khóa đã lưu
     * @param status trạng thái đã lưu
     * @param bookingCode mã đơn đã lưu, có thể null với khóa vận hành
     * @param reason lý do đã lưu, có thể null
     * @param holdExpiresAt hạn đã lưu, có thể null ngoài trạng thái HELD
     * @param createdAt thời điểm tạo đã lưu
     * @param statusChangedAt thời điểm đổi trạng thái đã lưu
     * @return aggregate phản ánh dữ liệu đã lưu
     * @throws DomainException nếu dữ liệu vi phạm bất biến cấu trúc
     */
    public static Reservation restore(
            String code,
            long vehicleId,
            ReservationPeriod period,
            ReservationKind kind,
            ReservationStatus status,
            String bookingCode,
            String reason,
            Instant holdExpiresAt,
            Instant createdAt,
            Instant statusChangedAt
    ) {
        return new Reservation(
                code,
                vehicleId,
                period,
                kind,
                status,
                bookingCode,
                reason,
                holdExpiresAt,
                createdAt,
                statusChangedAt
        );
    }

    /**
     * Xác nhận một chỗ giữ còn hạn theo BR-103 và status-flow §2.
     *
     * <p>Chỉ cho phép HELD chuyển sang CONFIRMED.
     * Tại đúng thời điểm hết hạn, chỗ giữ không còn được xác nhận.
     *
     * <p>Không tự kiểm thanh toán: bên gọi chịu trách nhiệm xác nhận
     * cọc và điều phối transaction của đơn thuê.
     *
     * @param changedAt thời điểm thực hiện lấy từ Clock chung
     * @return bản mới ở trạng thái CONFIRMED
     * @throws DomainException nếu thiếu thời điểm, sai trạng thái
     *                         hoặc chỗ giữ đã hết hạn
     */
    public Reservation confirm(Instant changedAt) {
        Instant transitionTime = Validations.required(changedAt, "changedAt");
        requireStatus(ReservationStatus.HELD);

        if (!transitionTime.isBefore(holdExpiresAt)) {
            throw DomainException.ruleViolation(ErrorCode.HOLD_EXPIRED);
        }

        return changeStatus(ReservationStatus.CONFIRMED, transitionTime);
    }

    /**
     * Giải phóng chỗ giữ hoặc chỗ đã xác nhận theo BR-103, BR-304.
     *
     * <p>Cho phép HELD hoặc CONFIRMED chuyển sang RELEASED.
     * Chỗ giữ có thể được nhả trước hạn khi khách hủy;
     * job chỉ gọi đường ghi giải phóng với bản ghi đã quá hạn.
     *
     * <p>Không dùng thao tác này để kết thúc chuyến đang chạy
     * hoặc hoàn tất khóa vận hành.
     *
     * @param changedAt thời điểm thực hiện lấy từ Clock chung
     * @return bản mới ở trạng thái RELEASED
     * @throws DomainException nếu thiếu thời điểm hoặc sai trạng thái
     */
    public Reservation release(Instant changedAt) {
        Instant transitionTime = Validations.required(changedAt, "changedAt");

        if (status != ReservationStatus.HELD
                && status != ReservationStatus.CONFIRMED) {
            throw DomainException.ruleViolation(
                    ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION
            );
        }

        return changeStatus(ReservationStatus.RELEASED, transitionTime);
    }

    /**
     * Đánh dấu xe đã được bàn giao theo status-flow §2.
     *
     * <p>Chỉ cho phép CONFIRMED chuyển sang IN_USE.
     * Các điều kiện thanh toán và bàn giao thuộc bên gọi,
     * không được kiểm bằng cách import module khác vào domain.
     *
     * @param changedAt thời điểm thực hiện lấy từ Clock chung
     * @return bản mới ở trạng thái IN_USE
     * @throws DomainException nếu thiếu thời điểm hoặc sai trạng thái
     */
    public Reservation markInUse(Instant changedAt) {
        Instant transitionTime = Validations.required(changedAt, "changedAt");
        requireStatus(ReservationStatus.CONFIRMED);

        return changeStatus(ReservationStatus.IN_USE, transitionTime);
    }

    /**
     * Hoàn tất chuyến hoặc công việc vận hành theo status-flow §2.
     *
     * <p>Cho phép IN_USE hoặc khóa vận hành hữu hạn BLOCKED chuyển sang COMPLETED.
     * COMPLIANCE_HOLD luôn BLOCKED theo BR-015, không được hoàn tất hoặc giải phóng.
     * Giữ nguyên khoảng đã lưu để không làm mất đệm theo
     * BR-109, BR-116 và database-guideline §4.
     *
     * <p>Không co khoảng theo giờ trả thật. Gia hạn giấy tờ phải dời điểm bắt đầu
     * của chính khóa theo BR-015, không dùng thao tác hoàn tất.
     *
     * @param changedAt thời điểm thực hiện lấy từ Clock chung
     * @return bản mới ở trạng thái COMPLETED
     * @throws DomainException nếu thiếu thời điểm hoặc sai trạng thái
     */
    public Reservation complete(Instant changedAt) {
        Instant transitionTime = Validations.required(changedAt, "changedAt");

        if (kind == ReservationKind.COMPLIANCE_HOLD
                || (status != ReservationStatus.IN_USE
                && status != ReservationStatus.BLOCKED)) {
            throw DomainException.ruleViolation(
                    ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION
            );
        }

        return changeStatus(ReservationStatus.COMPLETED, transitionTime);
    }

    /**
     * Dời điểm bắt đầu khóa giấy tờ theo BR-015; chỉ gọi khi giấy tờ được gia hạn.
     *
     * <p>Chỉ COMPLIANCE_HOLD/BLOCKED được thu hẹp khoảng bằng mốc mới sau mốc cũ.
     * Giữ nguyên cận trên không chặn, định danh và mọi trường khác, kể cả status_changed_at.
     * Không đọc Clock, không kết thúc khóa và không tạo định danh mới.
     *
     * @param newStartInclusive mốc bắt đầu mới, bắt buộc sau mốc đang lưu
     * @return aggregate mới có khoảng thu hẹp; bản nguồn không bị sửa
     * @throws DomainException nếu thiếu mốc, mốc không tăng hoặc loại/trạng thái không cho phép
     */
    public Reservation moveComplianceHoldStart(Instant newStartInclusive) {
        Instant checkedStart = Validations.required(newStartInclusive, "newStartInclusive");
        if (kind != ReservationKind.COMPLIANCE_HOLD || status != ReservationStatus.BLOCKED) {
            throw DomainException.ruleViolation(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION);
        }
        if (!checkedStart.isAfter(period.startInclusive())) {
            throw DomainException.invalidInput("newStartInclusive must be after the current start.");
        }
        return new Reservation(code, vehicleId, ReservationPeriod.unboundedFrom(checkedStart),
                kind, status, bookingCode, reason, holdExpiresAt, createdAt, statusChangedAt);
    }

    /**
     * Kiểm trạng thái hiện tại trước một bước chuyển có một nguồn hợp lệ.
     *
     * @param expected trạng thái nguồn bắt buộc
     * @throws DomainException nếu trạng thái hiện tại không khớp
     */
    private void requireStatus(ReservationStatus expected) {
        if (status != expected) {
            throw DomainException.ruleViolation(
                    ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION
            );
        }
    }

    /**
     * Tạo bản mới sau khi phương thức nghiệp vụ đã kiểm bước chuyển.
     *
     * <p>Chỉ thay trạng thái và mốc đổi trạng thái.
     * Không sửa mã, xe, loại khóa, khoảng, mã đơn, lý do,
     * hạn giữ chỗ hoặc thời điểm tạo.
     *
     * @param newStatus trạng thái đích đã được kiểm tra
     * @param changedAt thời điểm thực hiện đã được kiểm tra
     * @return aggregate mới, không thay đổi đối tượng hiện tại
     */
    private Reservation changeStatus(
            ReservationStatus newStatus,
            Instant changedAt
    ) {
        return new Reservation(
                code,
                vehicleId,
                period,
                kind,
                newStatus,
                bookingCode,
                reason,
                holdExpiresAt,
                createdAt,
                changedAt
        );
    }

    /**
     * Trả mã nghiệp vụ dùng để định danh khóa lịch.
     *
     * @return mã reservation
     */
    public String code() {
        return code;
    }

    /**
     * Trả định danh logic của xe, không truy vấn module vehicle.
     *
     * @return định danh xe
     */
    public long vehicleId() {
        return vehicleId;
    }

    /**
     * Trả khoảng khóa đã lưu, bao gồm đệm nếu là đơn thuê.
     *
     * @return khoảng thời gian khóa
     */
    public ReservationPeriod period() {
        return period;
    }

    /**
     * Trả nguyên nhân khóa lịch.
     *
     * @return loại khóa
     */
    public ReservationKind kind() {
        return kind;
    }

    /**
     * Trả trạng thái hiện tại của bản aggregate này.
     *
     * @return trạng thái khóa
     */
    public ReservationStatus status() {
        return status;
    }

    /**
     * Trả mã đơn dùng để đối chiếu, không phải định danh reservation.
     *
     * @return mã đơn hoặc null với khóa vận hành
     */
    public String bookingCode() {
        return bookingCode;
    }

    /**
     * Trả nguyên văn lý do đã được cung cấp.
     *
     * @return lý do hoặc null
     */
    public String reason() {
        return reason;
    }

    /**
     * Trả hạn giữ chỗ đã tính khi tạo, không tính lại theo cấu hình mới.
     *
     * @return thời điểm hết hạn hoặc null nếu không có
     */
    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    /**
     * Trả thời điểm tạo khóa lịch.
     *
     * @return thời điểm tạo
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Trả thời điểm chuyển trạng thái gần nhất.
     *
     * @return thời điểm đổi trạng thái
     */
    public Instant statusChangedAt() {
        return statusChangedAt;
    }
}
