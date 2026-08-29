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
     *
     * <p>Mã được giữ nguyên như trong query.
     * Lỗi truy cập dữ liệu không được chuyển thành lỗi không tìm thấy.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra, không được null
     * @return thông tin chi tiết của xe tìm được
     * @throws DomainException nếu không tìm thấy xe,
     *                         với mã lỗi VEHICLE_NOT_FOUND
     */
    VehicleDetail get(GetVehicleQuery query);
}