package com.carrental.vehicle.application.view;

import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleStatus;

import java.time.LocalDate;

/**
 * Chứa dữ liệu chi tiết của xe đã được lưu.
 *
 * <p>Đây là mô hình đọc của application, không phải aggregate
 * và không phải response trả trực tiếp qua HTTP.
 *
 * <p>Loại sở hữu theo BR-001, liên kết chi nhánh theo BR-003,
 * giấy tờ theo BR-005, trạng thái duyệt theo BR-010
 * và loại nhiên liệu theo BR-410.
 *
 * <p>Khóa chính của xe và định danh chi nhánh phục vụ xử lý nội bộ.
 * REST adapter sẽ chuyển dữ liệu thành response riêng.
 * URL sử dụng mã nghiệp vụ của xe, không dùng khóa chính số.
 *
 * <p>Persistence chỉ điền dữ liệu trong schema vehicle, để branchCode null.
 * Application bổ sung branchCode qua branch.api trước khi trả use case cho REST;
 * không JOIN hoặc sao chép mã chi nhánh vào bảng xe (ADR-0008).
 *
 * <p>Không kiểm lại điều kiện duyệt khi đọc dữ liệu.
 * Hồ sơ xe thiếu ngày giấy tờ hoặc có giấy tờ đã hết hạn
 * vẫn phải biểu diễn được.
 *
 * @param id khóa chính nội bộ của xe do database sinh
 * @param code mã nghiệp vụ của xe
 * @param plateNumber biển số đã lưu
 * @param ownershipType loại sở hữu đã lưu
 * @param fuelType loại nhiên liệu đã lưu
 * @param branchId định danh chi nhánh liên kết, có thể null
 * @param status trạng thái hiện tại đã lưu
 * @param inspectionExpiresOn ngày đăng kiểm hết hiệu lực,
 *                            có thể null nếu chưa được cung cấp
 * @param liabilityInsuranceExpiresOn ngày bảo hiểm TNDS hết hiệu lực,
 *                                    có thể null nếu chưa được cung cấp
 * @param seats số chỗ theo BR-018
 * @param transmission hộp số theo BR-018
 * @param make hãng xe có nội dung
 * @param model dòng xe có nội dung
 * @param branchCode mã chi nhánh do application bổ sung; null trước tra cứu hoặc khi không có liên kết
 */
public record VehicleDetail(
        long id,
        String code,
        String plateNumber,
        OwnershipType ownershipType,
        FuelType fuelType,
        Long branchId,
        VehicleStatus status,
        LocalDate inspectionExpiresOn,
        LocalDate liabilityInsuranceExpiresOn,
        Integer seats,
        Transmission transmission,
        String make,
        String model,
        String branchCode
) {
    /** Tạo view đã bổ sung mã cho response, không đổi dữ liệu xe hoặc ghi mã vào CSDL. */
    public VehicleDetail withBranchCode(String code) {
        return new VehicleDetail(id, this.code, plateNumber, ownershipType, fuelType, branchId,
                status, inspectionExpiresOn, liabilityInsuranceExpiresOn, seats, transmission, make, model, code);
    }
}
