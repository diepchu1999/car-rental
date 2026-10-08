package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

/**
 * Gom bốn thuộc tính bắt buộc ngay khi tạo xe theo BR-018.
 * Hãng và dòng giữ nguyên cách viết; không đặt giới hạn độ dài chưa có BR.
 *
 * @param seats số chỗ thuộc tập 4, 5, 7, 16
 * @param transmission hộp số sàn hoặc tự động
 * @param make hãng xe có nội dung
 * @param model dòng xe có nội dung
 */
public record VehicleSpecifications(Integer seats, Transmission transmission, String make, String model) {

    /**
     * Kiểm đủ dữ liệu và tập số chỗ cho phép ở cả đường tạo mới lẫn khôi phục.
     *
     * @throws DomainException nếu thiếu dữ liệu, chuỗi trắng hoặc số chỗ ngoài BR-018
     */
    public VehicleSpecifications {
        seats = Validations.required(seats, "seats");
        transmission = Validations.required(transmission, "transmission");
        make = Validations.requiredText(make, "make");
        model = Validations.requiredText(model, "model");
        if (seats != 4 && seats != 5 && seats != 7 && seats != 16) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_SEATS);
        }
    }
}
