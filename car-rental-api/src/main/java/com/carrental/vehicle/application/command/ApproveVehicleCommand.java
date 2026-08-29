package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Chứa yêu cầu phê duyệt xe theo BR-010 và BR-005.
 *
 * <p>Command chỉ xác định xe cần duyệt bằng mã nghiệp vụ.
 * Không nhận trạng thái đích hoặc ngày duyệt từ request.
 *
 * <p>Application service xác định ngày duyệt và tải xe.
 * Vehicle kiểm trạng thái PENDING_APPROVAL cùng điều kiện giấy tờ
 * trước khi tạo bản ở trạng thái ACTIVE.
 *
 * <p>Command không tự thực hiện kiểm tra phân quyền người duyệt.
 * Phần đó thuộc task xác thực và phân quyền.
 *
 * @param code mã nghiệp vụ của xe cần phê duyệt
 */
public record ApproveVehicleCommand(String code) {

    /**
     * Bảo đảm command chứa mã có nội dung và giữ nguyên giá trị đầu vào.
     *
     * <p>Không kiểm trạng thái hoặc giấy tờ tại đây.
     *
     * @throws DomainException nếu mã là null, rỗng hoặc chỉ chứa khoảng trắng
     */
    public ApproveVehicleCommand {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo yêu cầu phê duyệt từ mã xe, không phụ thuộc DTO của adapter.
     *
     * @param code mã xe đầu vào, có thể null nếu bị thiếu
     * @return command đã được kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static ApproveVehicleCommand from(String code) {
        return new ApproveVehicleCommand(code);
    }
}