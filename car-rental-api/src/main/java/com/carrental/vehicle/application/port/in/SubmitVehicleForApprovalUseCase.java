package com.carrental.vehicle.application.port.in;

import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.view.VehicleDetail;

/**
 * Cổng đầu vào cho chức năng gửi hồ sơ xe để chờ duyệt.
 *
 * <p>Thực hiện bước DRAFT sang PENDING_APPROVAL
 * theo BR-010 và status-flow mục 4.
 */
public interface SubmitVehicleForApprovalUseCase {

    /**
     * Gửi duyệt xe có mã được yêu cầu.
     *
     * <p>BR-810: ADMIN gửi mọi chi nhánh; STAFF/MANAGER chỉ gửi xe của chi nhánh mình.
     * Phạm vi dựa trên xe đã lưu, không nhận từ dữ liệu client tự khai.
     *
     * <p>Phần hiện thực tải xe, gọi hành vi gửi duyệt của aggregate,
     * lưu thay đổi và đọc lại dữ liệu trước khi trả kết quả.
     *
     * <p>Không cho phép bên gọi tự chọn trạng thái đích.
     * Trạng thái không hợp lệ khi đọc là lỗi quy tắc; nếu trạng thái
     * bị thay đổi trước lúc ghi thì báo xung đột và không tự thử lại.
     *
     * @param command yêu cầu gửi duyệt đã được kiểm tra, không được null
     * @return thông tin chi tiết của xe sau khi lưu thay đổi
     * @throws DomainException với VEHICLE_NOT_FOUND nếu xe không tồn tại,
     *                         hoặc VEHICLE_INVALID_STATUS_TRANSITION
     *                         nếu trạng thái không cho phép gửi duyệt;
     *                         từ chối khi chưa xác thực, sai quyền hoặc chưa gán chi nhánh
     */
    VehicleDetail submitForApproval(
            SubmitVehicleForApprovalCommand command
    );
}
