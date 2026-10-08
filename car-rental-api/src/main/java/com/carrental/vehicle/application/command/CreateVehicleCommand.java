package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleSpecifications;
import com.carrental.vehicle.domain.Transmission;

import java.time.LocalDate;

/**
 * Chứa đầu vào đã kiểm tra cho yêu cầu tạo xe.
 *
 * <p>Loại sở hữu theo BR-001, liên kết chi nhánh theo BR-003,
 * giấy tờ theo BR-005 và loại nhiên liệu theo BR-410.
 *
 * <p>Đường tạo xe giai đoạn 1 bắt buộc cung cấp mã chi nhánh.
 * Application service tra cứu mã này qua BranchDirectory
 * để lấy định danh nội bộ trước khi lưu xe.
 *
 * <p>API giai đoạn 1 cung cấp cố định OwnershipType.COMPANY,
 * không nhận loại sở hữu tùy ý từ người gọi HTTP.
 * Command chỉ giữ và truyền giá trị, không rẽ nhánh theo loại sở hữu.
 *
 * <p>Mã xe do application service sinh.
 * Trạng thái ban đầu do Vehicle.createDraft xác định.
 * Cả hai không thuộc đầu vào của command.
 *
 * @param plateNumber biển số có nội dung, được giữ nguyên
 * @param ownershipType loại sở hữu do tầng gọi cung cấp
 * @param fuelType loại nhiên liệu của xe
 * @param branchCode mã nghiệp vụ của chi nhánh cần liên kết
 * @param documents thông tin hạn giấy tờ, không được null
 * @param specifications thuộc tính xe đã kiểm tra theo BR-018
 */
public record CreateVehicleCommand(
        String plateNumber,
        OwnershipType ownershipType,
        FuelType fuelType,
        String branchCode,
        VehicleDocuments documents,
        VehicleSpecifications specifications
) {

    /**
     * Bảo đảm command luôn có đủ cấu trúc đầu vào bắt buộc.
     *
     * <p>Kiểm ngay trong constructor để cả việc tạo trực tiếp
     * lẫn việc tạo qua factory đều giữ cùng hợp đồng.
     *
     * <p>Giữ nguyên biển số và mã chi nhánh,
     * không tự cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * <p>Không kiểm định dạng sinh mã chi nhánh tại đây.
     * Mã không tồn tại được application xử lý sau khi tra cứu.
     *
     * <p>Không kiểm hạn giấy tờ hoặc truy cập database.
     *
     * @throws DomainException nếu thiếu trường bắt buộc
     *                         hoặc chuỗi bắt buộc không có nội dung
     */
    public CreateVehicleCommand {
        plateNumber = Validations.requiredText(
                plateNumber,
                "plateNumber"
        );
        ownershipType = Validations.required(
                ownershipType,
                "ownershipType"
        );
        fuelType = Validations.required(
                fuelType,
                "fuelType"
        );
        branchCode = Validations.requiredText(
                branchCode,
                "branchCode"
        );
        specifications = Validations.required(specifications, "specifications");
        documents = Validations.required(
                documents,
                "documents"
        );
    }

    /**
     * Tạo command từ các giá trị đầu vào, không phụ thuộc DTO của adapter.
     *
     * <p>Hai ngày hết hạn được gom thành VehicleDocuments.
     * Ngày bị thiếu vẫn giữ null, không thay bằng ngày hiện tại
     * hoặc một ngày mặc định.
     *
     * <p>Giấy tờ thiếu hoặc hết hạn chưa ngăn tạo hồ sơ bản nháp.
     * Điều kiện đó được kiểm khi duyệt theo BR-005.
     *
     * @param plateNumber biển số đầu vào
     * @param ownershipType loại sở hữu do tầng gọi cung cấp
     * @param fuelType loại nhiên liệu đầu vào
     * @param branchCode mã chi nhánh đầu vào
     * @param inspectionExpiresOn ngày đăng kiểm hết hiệu lực, có thể null
     * @param liabilityInsuranceExpiresOn ngày bảo hiểm TNDS hết hiệu lực,
     *                                    có thể null
     * @param seats số chỗ theo BR-018
     * @param transmission hộp số
     * @param make hãng xe
     * @param model dòng xe
     * @return command đã được kiểm tra cấu trúc đầu vào
     * @throws DomainException nếu thiếu trường bắt buộc
     *                         hoặc chuỗi bắt buộc không có nội dung
     */
    public static CreateVehicleCommand from(
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            String branchCode,
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn,
            Integer seats,
            Transmission transmission,
            String make,
            String model
    ) {
        return new CreateVehicleCommand(
                plateNumber,
                ownershipType,
                fuelType,
                branchCode,
                new VehicleDocuments(
                        inspectionExpiresOn,
                        liabilityInsuranceExpiresOn
                ),
                new VehicleSpecifications(seats, transmission, make, model)
        );
    }
}
