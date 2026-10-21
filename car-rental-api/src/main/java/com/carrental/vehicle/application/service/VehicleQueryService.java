package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
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
    private final CallerDirectory callers;

    /**
     * Khởi tạo service với cổng đọc thông tin xe.
     *
     * @param readVehiclePort cổng truy xuất thông tin xe
     * @param branchDirectory cổng tra mã chi nhánh từ tham chiếu ID nội bộ
     * @param callers danh tính đã xác thực trong request, không suy quyền từ tham số client
     */
    VehicleQueryService(ReadVehiclePort readVehiclePort, BranchDirectory branchDirectory, CallerDirectory callers) {
        this.readVehiclePort = readVehiclePort;
        this.branchDirectory = branchDirectory;
        this.callers = callers;
    }

    /**
     * Lấy thông tin của xe có mã được yêu cầu.
     * ADMIN xem mọi xe; STAFF/MANAGER chỉ xem xe thuộc chi nhánh mình (BR-803/810).
     * Kiểm phạm vi trước khi tra mã chi nhánh, không kiểm lại trạng thái hoặc giấy tờ lúc duyệt.
     *
     * <p>Lỗi truy cập dữ liệu được truyền nguyên trạng, không bị chuyển
     * thành lỗi không tìm thấy xe.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra đầu vào
     * @return thông tin xe tìm được
     * @throws DomainException nếu sai quyền, chưa gán chi nhánh hoặc không tồn tại xe
     */
    @Override
    @Transactional(readOnly = true)
    public VehicleDetail get(GetVehicleQuery query) {
        CallerRef caller = callers.current();
        boolean admin = caller.hasRole("ADMIN");
        if (!admin) {
            if (!caller.hasRole("BRANCH_STAFF") && !caller.hasRole("BRANCH_MANAGER")) {
                throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
            }
            if (caller.branchId() == null) {
                throw DomainException.forbidden(ErrorCode.STAFF_BRANCH_REQUIRED);
            }
        }
        VehicleDetail detail = readVehiclePort.findByCode(query.code())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.VEHICLE_NOT_FOUND));
        if (!admin && !caller.branchId().equals(detail.branchId())) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        return VehicleDetailEnricher.enrich(detail, branchDirectory);
    }
}
