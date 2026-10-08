package com.carrental.vehicle.adapter.in.rest.admin.response;

import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleStatus;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Hợp đồng HTTP quản trị hồ sơ xe, tách biệt với view lưu trữ.
 *
 * <p>Không trả khóa chính số của xe hoặc chi nhánh theo R12.
 * branchCode do application tra qua branch.api theo BR-003 và ADR-0008.
 * Không JOIN qua schema hoặc suy mã chi nhánh từ khóa chính.
 *
 * @param code mã xe dùng trên URL
 * @param plateNumber biển số
 * @param ownershipType loại sở hữu theo BR-001
 * @param fuelType loại nhiên liệu theo BR-410
 * @param branchCode mã chi nhánh, có thể null nếu dữ liệu không có liên kết chi nhánh
 * @param status trạng thái hiện tại
 * @param inspectionExpiresOn ngày đăng kiểm hết hiệu lực
 * @param liabilityInsuranceExpiresOn ngày TNDS hết hiệu lực
 * @param seats số chỗ theo BR-018
 * @param transmission hộp số theo BR-018
 * @param make hãng xe có nội dung
 * @param model dòng xe có nội dung
 */
public record VehicleResponse(
        String code,
        String plateNumber,
        OwnershipType ownershipType,
        FuelType fuelType,
        String branchCode,
        VehicleStatus status,
        LocalDate inspectionExpiresOn,
        LocalDate liabilityInsuranceExpiresOn,
        Integer seats,
        Transmission transmission,
        String make,
        String model
) {

    /**
     * Chọn các trường thuộc hợp đồng HTTP từ application view.
     *
     * @param view dữ liệu do use case trả về
     * @return response không chứa khóa chính xe
     */
    public static VehicleResponse fromDomain(VehicleDetail view) {
        Objects.requireNonNull(view, "view must not be null.");
        if (view.branchId() != null && view.branchCode() == null) {
            throw new IllegalStateException("Vehicle branch code must be resolved before creating a response.");
        }
        return new VehicleResponse(
                view.code(), view.plateNumber(), view.ownershipType(), view.fuelType(),
                view.branchCode(), view.status(), view.inspectionExpiresOn(),
                view.liabilityInsuranceExpiresOn(),
                view.seats(), view.transmission(), view.make(), view.model()
        );
    }
}
