package com.carrental.booking.application.query;

import com.carrental.shared.validation.Validations;

/** Mã đơn trên URL; chủ đơn luôn lấy từ CallerDirectory, không nhận từ người gọi. */
public record GetBookingQuery(String code) {
    /** Thiếu mã là đầu vào sai; mã không tồn tại được use case trả BOOKING_NOT_FOUND. */
    public GetBookingQuery { code = Validations.requiredText(code, "code"); }

    /** Nhận mã thô tại biên HTTP. */
    public static GetBookingQuery from(String code) { return new GetBookingQuery(code); }
}
