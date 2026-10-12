package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.math.BigDecimal;

/**
 * Giá ngày nguyên VND của một xe, dương và chia hết cho 1.000 theo BR-234.
 * @param amount số tiền nguyên VND, không phải tỷ lệ hoặc số tiền đã nhân mức tăng
 */
public record DailyRate(long amount) {
    /** Kiểm bất biến BR-234; không tự làm tròn giá do admin nhập. */
    public DailyRate {
        if (amount <= 0 || amount % 1_000 != 0) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_DAILY_RATE);
        }
    }

    /** Chuyển đầu vào chính xác sang VND; thiếu, lẻ hoặc vượt miền BIGINT đều bị từ chối. */
    public static DailyRate from(BigDecimal value) {
        if (value == null) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_DAILY_RATE);
        }
        try {
            return new DailyRate(value.longValueExact());
        } catch (ArithmeticException failure) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_DAILY_RATE);
        }
    }

    /** Khôi phục giá có thể thiếu của hồ sơ chưa duyệt, không tự điền giá mặc định. */
    public static DailyRate ofNullable(Long amount) {
        return amount == null ? null : new DailyRate(amount);
    }
}
