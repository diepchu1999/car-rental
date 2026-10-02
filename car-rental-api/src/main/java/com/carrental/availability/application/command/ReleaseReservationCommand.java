package com.carrental.availability.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Yêu cầu nhả chỗ giữ hoặc chỗ đã xác nhận theo BR-103, BR-304 và status-flow §2.
 *
 * <p>Chỉ định danh bằng mã khóa lịch, không phải mã đơn thuê.
 * Bên gọi không truyền trạng thái đích hoặc thời điểm chuyển.
 *
 * @param code mã khóa lịch cần thao tác
 */
public record ReleaseReservationCommand(String code) {

    /**
     * Kiểm mã có nội dung, giữ nguyên giá trị để không đổi định danh.
     *
     * @throws DomainException nếu mã thiếu hoặc chỉ chứa khoảng trắng
     */
    public ReleaseReservationCommand {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo command từ mã thô, không phụ thuộc DTO của adapter.
     *
     * @param code mã khóa lịch đầu vào
     * @return command đã kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static ReleaseReservationCommand from(String code) {
        return new ReleaseReservationCommand(code);
    }
}
