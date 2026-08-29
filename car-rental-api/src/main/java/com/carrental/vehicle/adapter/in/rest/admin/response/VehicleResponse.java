package com.carrental.vehicle.adapter.in.rest.admin.response;

import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleStatus;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Hợp đồng HTTP quản trị hồ sơ xe, tách biệt với view lưu trữ.
 *
 * <p>Không trả khóa chính của xe. branchId là tham chiếu chỉ đọc của
 * chi nhánh theo BR-003; request và URL vẫn sử dụng mã nghiệp vụ.
 * Không JOIN qua schema hoặc suy mã chi nhánh từ khóa chính.
 *
 * @param code mã xe dùng trên URL
 * @param plateNumber biển số
 * @param ownershipType loại sở hữu theo BR-001
 * @param fuelType loại nhiên liệu theo BR-410
 * @param branchId tham chiếu chi nhánh, có thể null ở dữ liệu không thuộc luồng tạo GĐ1
 * @param status trạng thái hiện tại
 * @param inspectionExpiresOn ngày đăng kiểm hết hiệu lực
 * @param liabilityInsuranceExpiresOn ngày TNDS hết hiệu lực
 */
public record VehicleResponse(
        String code,
        String plateNumber,
        OwnershipType ownershipType,
        FuelType fuelType,
        Long branchId,
        VehicleStatus status,
        LocalDate inspectionExpiresOn,
        LocalDate liabilityInsuranceExpiresOn
) {

    /**
     * Chọn các trường thuộc hợp đồng HTTP từ application view.
     *
     * @param view dữ liệu do use case trả về
     * @return response không chứa khóa chính xe
     */
    public static VehicleResponse fromDomain(VehicleDetail view) {
        Objects.requireNonNull(view, "view must not be null.");
        return new VehicleResponse(
                view.code(), view.plateNumber(), view.ownershipType(), view.fuelType(),
                view.branchId(), view.status(), view.inspectionExpiresOn(),
                view.liabilityInsuranceExpiresOn()
        );
    }
}
