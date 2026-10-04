package com.carrental.availability.application.command;

import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.time.Instant;

/**
 * Đầu vào khóa vận hành theo BR-007, BR-011, BR-012, BR-015 và BR-104.
 *
 * <p>Không nhận mã khóa, mã đơn, TTL hoặc đệm thuê xe. Khoảng vận hành được giữ nguyên.
 * COMPLIANCE_HOLD bắt buộc không chặn trên; các loại vận hành khác phải hữu hạn.
 * RENTAL phải đi qua luồng hold.
 *
 * @param vehicleId định danh xe lớn hơn không
 * @param period khoảng vận hành cần khóa
 * @param kind nguyên nhân khóa không phải RENTAL
 * @param reason lý do giữ nguyên văn, có thể null theo DDL đã duyệt
 */
public record BlockReservationCommand(
        long vehicleId, ReservationPeriod period, ReservationKind kind, String reason
) {

    /**
     * Kiểm hình dạng đầu vào trước khi service đọc Clock hoặc sinh mã.
     *
     * @throws DomainException nếu xe, khoảng hoặc loại khóa không hợp lệ
     */
    public BlockReservationCommand {
        if (vehicleId <= 0L) {
            throw DomainException.invalidInput("vehicleId must be greater than zero.");
        }
        period = Validations.required(period, "period");
        kind = Validations.required(kind, "kind");
        if (kind == ReservationKind.RENTAL) {
            throw DomainException.invalidInput("RENTAL must be created through the hold workflow.");
        }
        if (period.isUnbounded() && kind != ReservationKind.COMPLIANCE_HOLD) {
            throw DomainException.invalidInput("Only COMPLIANCE_HOLD may have an unbounded period.");
        }
        if (kind == ReservationKind.COMPLIANCE_HOLD && !period.isUnbounded()) {
            throw DomainException.invalidInput("COMPLIANCE_HOLD must have an unbounded period.");
        }
    }

    /**
     * Tạo command từ định danh, đầu mút và loại khóa; null ở cận trên biểu diễn không chặn trên.
     *
     * @param vehicleId định danh xe, có thể null nếu thiếu
     * @param startInclusive thời điểm bắt đầu, bắt buộc
     * @param endExclusive cận trên mở; bắt buộc null với COMPLIANCE_HOLD, khác null với loại còn lại
     * @param kind nguyên nhân khóa vận hành
     * @param reason lý do tùy chọn, không cắt khoảng trắng
     * @return command đã kiểm tra
     * @throws DomainException nếu dữ liệu không hợp lệ
     */
    public static BlockReservationCommand from(
            Long vehicleId, Instant startInclusive, Instant endExclusive, ReservationKind kind, String reason
    ) {
        return new BlockReservationCommand(Validations.required(vehicleId, "vehicleId"),
                new ReservationPeriod(startInclusive, endExclusive), kind, reason);
    }
}
