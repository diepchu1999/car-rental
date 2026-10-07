package com.carrental.search.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import java.util.Set;

/**
 * Bộ lọc BR-018/126 tại biên tìm kiếm; không import enum nội bộ của vehicle.
 * Kiểm trước khi tìm chi nhánh để đầu vào sai vẫn báo lỗi khi không có ứng viên.
 * Null nghĩa là không lọc; chuỗi có gửi nhưng trắng hoặc enum lạ không bị bỏ qua.
 */
public record VehicleSearchFilters(Integer seats, String transmission, String fuelType,
        String make, String model, Boolean collateralFree) {
    /** Kiểm hợp đồng công khai của vehicle, chuẩn hóa trắng hãng/dòng nhưng không giới hạn độ dài. */
    public VehicleSearchFilters {
        if (seats != null && !Set.of(4, 5, 7, 16).contains(seats)) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_SEATS);
        }
        validateChoice(transmission, Set.of("MANUAL", "AUTOMATIC"), "transmission");
        validateChoice(fuelType, Set.of("PETROL", "DIESEL", "ELECTRIC", "HYBRID"), "fuelType");
        make = make == null ? null : Validations.requiredText(make, "make").strip();
        model = model == null ? null : Validations.requiredText(model, "model").strip();
    }

    /** Biểu diễn yêu cầu không có bộ lọc thuộc tính, không suy giá trị mặc định cho từng thuộc tính. */
    public static VehicleSearchFilters none() {
        return new VehicleSearchFilters(null, null, null, null, null, null);
    }

    /** Enum dạng chuỗi phải đúng tên đã công bố; không tự sửa chữ hoặc đoán giá trị gần nhất. */
    private static void validateChoice(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) {
            throw DomainException.invalidInput(field + " is invalid.");
        }
    }
}
