package com.carrental.booking.application.command;

import com.carrental.shared.validation.Validations;

/** BR-301: chỉ nhận mã đơn; chủ và mốc huỷ do hệ thống xác định. */
public record CancelBookingCommand(String code) {
    /** Thiếu mã là đầu vào sai; mã không tồn tại và sai chủ cùng trả BOOKING_NOT_FOUND. */
    public CancelBookingCommand { code = Validations.requiredText(code, "code"); }

    /** Nhận mã tại biên gọi use case, không nhận giá hoặc chủ tự khai. */
    public static CancelBookingCommand from(String code) { return new CancelBookingCommand(code); }
}
