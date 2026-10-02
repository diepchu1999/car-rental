package com.carrental.availability.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Yêu cầu đánh dấu xe đang được sử dụng theo status-flow §2.
 *
 * <p>Chỉ định danh bằng mã khóa lịch, không phải mã đơn thuê.
 * Bên gọi không truyền trạng thái đích hoặc thời điểm chuyển.
 *
 * @param code mã khóa lịch cần thao tác
 */
public record MarkReservationInUseCommand(String code) {

    /**
     * Kiểm mã có nội dung, giữ nguyên giá trị để không đổi định danh.
     *
     * @throws DomainException nếu mã thiếu hoặc chỉ chứa khoảng trắng
     */
    public MarkReservationInUseCommand {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo command từ mã thô, không phụ thuộc DTO của adapter.
     *
     * @param code mã khóa lịch đầu vào
     * @return command đã kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static MarkReservationInUseCommand from(String code) {
        return new MarkReservationInUseCommand(code);
    }
}
