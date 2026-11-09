package com.carrental.identity.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import java.time.Instant;
import java.util.Set;

/** BR-106/106b/128/130: đầu vào cổng tự lái nội bộ; tài khoản/thuộc tính xe lấy từ nguồn tin cậy của bên gọi. */
public record CheckRentalEligibilityQuery(String accountCode, int seats, String transmission, Instant tripEnd) {
    /** Kiểm cả constructor; giữ hợp đồng BR-018 mà không import domain của module vehicle. */
    public CheckRentalEligibilityQuery {
        if (accountCode == null || !accountCode.matches("TK-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("Account code must be a valid business code.");
        }
        if (!Set.of(4, 5, 7, 16).contains(seats)) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_SEATS);
        }
        if (!"MANUAL".equals(transmission) && !"AUTOMATIC".equals(transmission)) {
            throw DomainException.invalidInput("Transmission must be MANUAL or AUTOMATIC.");
        }
        tripEnd = Validations.required(tripEnd, "tripEnd");
    }

    /** Tạo query từ cổng nội bộ, không nhận DTO, khoảng đệm hoặc thời điểm hiện tại do client khai. */
    public static CheckRentalEligibilityQuery from(String accountCode, int seats, String transmission, Instant tripEnd) {
        return new CheckRentalEligibilityQuery(accountCode, seats, transmission, tripEnd);
    }

    /** Không tự in tài khoản hoặc thời điểm chuyến vào log. */
    @Override public String toString() { return "CheckRentalEligibilityQuery[redacted]"; }
}
