package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.domain.DailyRate;
import java.math.BigDecimal;

/**
 * Đầu vào đổi giá BR-234; không nhận trạng thái hoặc dữ liệu xe khác.
 * @param code mã nghiệp vụ xe, giữ nguyên đầu vào
 * @param dailyRate giá ngày hợp lệ, không được null
 */
public record ChangeVehicleDailyRateCommand(String code, DailyRate dailyRate) {
    /** Kiểm cả đường tạo trực tiếp, không cho null biến thành thao tác xóa giá. */
    public ChangeVehicleDailyRateCommand {
        code = Validations.requiredText(code, "code");
        if (dailyRate == null) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_DAILY_RATE);
        }
    }

    /** Nhận giá trị thô từ biên và chuyển thành giá ngày hợp lệ, không phụ thuộc DTO. */
    public static ChangeVehicleDailyRateCommand from(String code, BigDecimal dailyRate) {
        return new ChangeVehicleDailyRateCommand(code, DailyRate.from(dailyRate));
    }
}
