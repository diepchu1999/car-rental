package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.port.in.GetVehicleUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điều phối việc tra cứu thông tin xe theo mã nghiệp vụ.
 *
 * <p>Service truy cập dữ liệu thông qua cổng đọc và chuyển trường hợp
 * không tìm thấy xe thành lỗi ứng dụng tương ứng.
 */
@Service
class VehicleQueryService implements GetVehicleUseCase {

    private final ReadVehiclePort readVehiclePort;
    private final BranchDirectory branchDirectory;

    /**
     * Khởi tạo service với cổng đọc thông tin xe.
     *
     * @param readVehiclePort cổng truy xuất thông tin xe
     * @param branchDirectory cổng tra mã chi nhánh từ tham chiếu ID nội bộ
     */
    VehicleQueryService(ReadVehiclePort readVehiclePort, BranchDirectory branchDirectory) {
        this.readVehiclePort = readVehiclePort;
        this.branchDirectory = branchDirectory;
    }

    /**
     * Lấy thông tin của xe có mã được yêu cầu.
     *
     * <p>Lỗi truy cập dữ liệu được truyền nguyên trạng, không bị chuyển
     * thành lỗi không tìm thấy xe.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra đầu vào
     * @return thông tin xe tìm được
     * @throws DomainException nếu không tồn tại xe có mã tương ứng
     */
    @Override
    @Transactional(readOnly = true)
    public VehicleDetail get(GetVehicleQuery query) {
        return readVehiclePort.findByCode(query.code())
                .map(detail -> VehicleDetailEnricher.enrich(detail, branchDirectory))
                .orElseThrow(() ->
                        DomainException.notFound(ErrorCode.VEHICLE_NOT_FOUND));
    }
}
