package com.carrental.vehicle.adapter.in.rest.admin.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.Transmission;

import java.time.LocalDate;
import java.math.BigDecimal;

/**
 * Dữ liệu tạo xe công ty trong giai đoạn 1 theo BR-001, BR-003 và BR-410.
 *
 * <p>Không nhận loại sở hữu, trạng thái hoặc mã xe do client chọn.
 * Hai ngày giấy tờ được phép thiếu lúc tạo bản nháp theo BR-005.
 * Validation thuộc command và domain, không thực hiện trong DTO này.
 * Các trường ngoài hợp đồng được bỏ qua, không được ánh xạ vào command.
 *
 * @param plateNumber biển số giữ nguyên đầu vào
 * @param fuelType loại nhiên liệu
 * @param branchCode mã chi nhánh đã tồn tại
 * @param inspectionExpiresOn ngày đăng kiểm hết hiệu lực, có thể null
 * @param liabilityInsuranceExpiresOn ngày TNDS hết hiệu lực, có thể null
 * @param seats số chỗ theo BR-018
 * @param transmission hộp số theo BR-018
 * @param make hãng xe có nội dung
 * @param model dòng xe có nội dung
 * @param dailyRate giá ngày tùy chọn theo BR-234; giữ số thập phân để domain từ chối thay vì cắt phần lẻ
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateVehicleRequest(
        String plateNumber,
        FuelType fuelType,
        String branchCode,
        LocalDate inspectionExpiresOn,
        LocalDate liabilityInsuranceExpiresOn,
        Integer seats,
        Transmission transmission,
        String make,
        String model,
        BigDecimal dailyRate
) {
}
