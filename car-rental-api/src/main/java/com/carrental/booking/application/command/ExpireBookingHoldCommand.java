package com.carrental.booking.application.command;

import com.carrental.shared.validation.Validations;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** BR-103: một ứng viên của job, không nhận TTL hoặc danh tính khách từ HTTP. */
public record ExpireBookingHoldCommand(String bookingCode, String reservationCode, Instant expiredAt) {
    /** Mã có thể thuộc hold nội bộ tạo trước module booking; mốc dùng chung cả lượt dọn. */
    public ExpireBookingHoldCommand {
        bookingCode = Validations.requiredText(bookingCode, "bookingCode");
        reservationCode = Validations.requiredText(reservationCode, "reservationCode");
        expiredAt = Validations.required(expiredAt, "expiredAt").truncatedTo(ChronoUnit.MICROS);
    }
}
