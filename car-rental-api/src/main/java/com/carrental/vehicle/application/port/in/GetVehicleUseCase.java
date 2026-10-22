package com.carrental.vehicle.application.port.in;

import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;

/**
 * Cổng đầu vào cho chức năng đọc chi tiết xe theo mã nghiệp vụ.
 *
 * <p>Chức năng chỉ đọc dữ liệu đã lưu, không thay đổi trạng thái
 * hoặc kiểm lại điều kiện phê duyệt.
 */
public interface GetVehicleUseCase {

    /**
     * Lấy thông tin chi tiết của xe có mã được yêu cầu.
     * ADMIN xem mọi xe; STAFF/MANAGER chỉ xem chi nhánh mình theo BR-803/810.
     * Sai vai hoặc khác chi nhánh trả ACCESS_DENIED; chưa gán trả STAFF_BRANCH_REQUIRED.
     * Yêu cầu danh tính HTTP qua CallerDirectory, không dùng làm cổng tra cứu cross-module.
     *
     * <p>Mã được giữ nguyên như trong query.
     * Lỗi truy cập dữ liệu không được chuyển thành lỗi không tìm thấy.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra, không được null
     * @return thông tin chi tiết của xe tìm được
     * @throws DomainException nếu không có quyền, chưa gán chi nhánh hoặc không tìm thấy xe
     */
    VehicleDetail get(GetVehicleQuery query);
}
