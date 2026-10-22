package com.carrental.vehicle.application.port.in;

import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.view.VehicleDetail;

/**
 * Cổng đầu vào cho chức năng phê duyệt xe theo BR-010 và BR-005.
 *
 * <p>Chỉ chuyển PENDING_APPROVAL sang ACTIVE khi giấy tờ
 * có đủ và còn hạn tại ngày duyệt, đồng thời đã có giá ngày (BR-234).
 */
public interface ApproveVehicleUseCase {

    /**
     * Phê duyệt xe có mã được yêu cầu.
     *
     * <p>Phần hiện thực xác định ngày duyệt, tải xe,
     * gọi hành vi phê duyệt của aggregate, lưu thay đổi
     * và đọc lại dữ liệu trước khi trả kết quả.
     *
     * <p>Ngày duyệt không lấy tùy ý từ request.
     * BR-810: chỉ ADMIN hoặc BRANCH_MANAGER của chi nhánh có xe được duyệt;
     * kiểm quyền trước điều kiện phê duyệt, không nhận phạm vi do client tự khai.
     * Nếu trạng thái thay đổi giữa lúc đọc và ghi, báo xung đột,
     * không tự duyệt lại dựa trên dữ liệu mới.
     *
     * @param command yêu cầu phê duyệt đã được kiểm tra, không được null
     * @return thông tin chi tiết của xe sau khi lưu thay đổi
     * @throws DomainException với VEHICLE_NOT_FOUND nếu xe không tồn tại,
     *                         VEHICLE_INVALID_STATUS_TRANSITION nếu sai trạng thái,
     *                         VEHICLE_DOCUMENT_MISSING nếu thiếu giấy tờ,
     *                         VEHICLE_DOCUMENT_EXPIRED nếu giấy tờ hết hạn,
     *                         VEHICLE_DAILY_RATE_REQUIRED nếu thiếu giá;
     *                         từ chối nếu chưa xác thực, sai quyền hoặc chưa gán chi nhánh
     */
    VehicleDetail approve(ApproveVehicleCommand command);
}
