package com.carrental.vehicle.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.Transmission;
import java.util.List;

/**
 * Đầu vào đọc xe theo BR-018/126; kiểu domain chỉ tồn tại bên trong module vehicle.
 * Null là bộ lọc vắng mặt; dữ liệu có gửi nhưng sai không bị bỏ qua âm thầm.
 */
public record ListSearchVehiclesQuery(List<Long> branchIds, RentalType rentalType,
        Integer seats, Transmission transmission, FuelType fuelType,
        String make, String model, Boolean collateralFree) {

    /** Kiểm danh sách ID, số chỗ và chuỗi; sao chép để bên gọi không đổi truy vấn sau validation. */
    public ListSearchVehiclesQuery {
        Validations.required(branchIds, "branchIds");
        if (branchIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw DomainException.invalidInput("branchIds must contain positive IDs.");
        }
        branchIds = branchIds.stream().distinct().toList();
        rentalType = Validations.required(rentalType, "rentalType");
        if (seats != null && seats != 4 && seats != 5 && seats != 7 && seats != 16) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_SEATS);
        }
        make = optionalText(make, "make");
        model = optionalText(model, "model");
    }

    /** Chuyển giá trị thô từ cổng module thành query; tên enum phải khớp đúng hợp đồng. */
    public static ListSearchVehiclesQuery from(List<Long> branchIds, RentalType rentalType,
            Integer seats, String transmission, String fuelType,
            String make, String model, Boolean collateralFree) {
        return new ListSearchVehiclesQuery(branchIds, rentalType, seats,
                optionalEnum(transmission, Transmission.class, "transmission"),
                optionalEnum(fuelType, FuelType.class, "fuelType"), make, model, collateralFree);
    }

    /** Chuỗi vắng mặt giữ null; chuỗi có nội dung được cắt khoảng trắng ở hai đầu. */
    private static String optionalText(String value, String field) {
        return value == null ? null : Validations.requiredText(value, field).strip();
    }

    /** Không để IllegalArgumentException của enum lọt thành lỗi nội bộ khi đầu vào sai. */
    private static <E extends Enum<E>> E optionalEnum(String value, Class<E> type, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw DomainException.invalidInput(field + " is invalid.");
        }
    }
}
