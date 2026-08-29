package com.carrental.vehicle.application.port.in;

import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.view.VehicleDetail;

/**
 * Cổng đầu vào cho chức năng tạo hồ sơ xe bản nháp.
 *
 * <p>Loại sở hữu theo BR-001, liên kết chi nhánh theo BR-003,
 * giấy tờ theo BR-005 và loại nhiên liệu theo BR-410.
 *
 * <p>Người gọi phụ thuộc hợp đồng này thay vì lớp service
 * hoặc cách lưu dữ liệu.
 */
public interface CreateVehicleUseCase {

    /**
     * Tạo xe từ dữ liệu đầu vào đã được kiểm tra.
     *
     * <p>Phần hiện thực tra cứu chi nhánh, sinh mã nghiệp vụ,
     * tạo aggregate ở DRAFT, lưu và đọc lại dữ liệu trước khi trả kết quả.
     *
     * <p>Không áp dụng điều kiện giấy tờ của bước phê duyệt
     * vào bước tạo hồ sơ bản nháp.
     *
     * @param command yêu cầu tạo xe đã được kiểm tra, không được null
     * @return thông tin chi tiết của xe đã được lưu
     * @throws DomainException với BRANCH_NOT_FOUND nếu chi nhánh không tồn tại,
     *                         hoặc VEHICLE_PLATE_ALREADY_EXISTS nếu biển số bị trùng
     */
    VehicleDetail create(CreateVehicleCommand command);
}