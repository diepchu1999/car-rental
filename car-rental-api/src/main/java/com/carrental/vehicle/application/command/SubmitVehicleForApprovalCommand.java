package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Chứa yêu cầu gửi hồ sơ xe để chờ duyệt theo BR-010 và status-flow mục 4.
 *
 * <p>Command chỉ xác định xe cần thao tác bằng mã nghiệp vụ.
 * Không nhận trạng thái đích từ bên gọi.
 *
 * <p>Application service tải xe, sau đó gọi hành vi của aggregate.
 * Vehicle chịu trách nhiệm chỉ cho phép DRAFT chuyển sang PENDING_APPROVAL.
 *
 * @param code mã nghiệp vụ của xe cần gửi duyệt
 */
public record SubmitVehicleForApprovalCommand(String code) {

    /**
     * Bảo đảm command chứa mã có nội dung và giữ nguyên giá trị đầu vào.
     *
     * <p>Không kiểm trạng thái hoặc giấy tờ tại đây.
     *
     * @throws DomainException nếu mã là null, rỗng hoặc chỉ chứa khoảng trắng
     */
    public SubmitVehicleForApprovalCommand {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo yêu cầu gửi duyệt từ mã xe, không phụ thuộc DTO của adapter.
     *
     * @param code mã xe đầu vào, có thể null nếu bị thiếu
     * @return command đã được kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static SubmitVehicleForApprovalCommand from(String code) {
        return new SubmitVehicleForApprovalCommand(code);
    }
}