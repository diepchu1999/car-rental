package com.carrental.vehicle.application.view;

import com.carrental.vehicle.domain.FuelType;
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
 * <p>Chỉ chứa tham chiếu chi nhánh được lưu cùng xe.
 * Khi cần thêm thông tin của chi nhánh, application phải lấy qua
 * cổng api của module branch, không JOIN trực tiếp qua schema.
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
        LocalDate liabilityInsuranceExpiresOn
) {
}