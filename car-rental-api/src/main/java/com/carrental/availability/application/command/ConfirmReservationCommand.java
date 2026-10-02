package com.carrental.availability.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Yêu cầu xác nhận chỗ giữ còn hạn theo BR-103 và status-flow §2.
 *
 * <p>Chỉ định danh bằng mã khóa lịch, không phải mã đơn thuê.
 * Bên gọi không truyền trạng thái đích hoặc thời điểm chuyển.
 *
 * @param code mã khóa lịch cần thao tác
 */
public record ConfirmReservationCommand(String code) {

    /**
     * Kiểm mã có nội dung, giữ nguyên giá trị để không đổi định danh.
     *
     * @throws DomainException nếu mã thiếu hoặc chỉ chứa khoảng trắng
     */
    public ConfirmReservationCommand {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo command từ mã thô, không phụ thuộc DTO của adapter.
     *
     * @param code mã khóa lịch đầu vào
     * @return command đã kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static ConfirmReservationCommand from(String code) {
        return new ConfirmReservationCommand(code);
    }
}
