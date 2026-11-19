package com.carrental.pricing.application.query;

import com.carrental.shared.validation.Validations;
import java.time.Instant;

/** BR-122: mã báo giá, khách đã xác thực và mốc tạo đơn do use case booking cung cấp. */
public record GetQuoteForBookingQuery(String code, String customerAccountCode, Instant at) {
    /** Không nhận chủ trống hoặc mốc mặc định; bên gọi phải truyền đúng danh tính đã xác thực. */
    public GetQuoteForBookingQuery {
        code = Validations.requiredText(code, "code");
        customerAccountCode = Validations.requiredText(customerAccountCode, "customerAccountCode");
        Validations.required(at, "at");
        if (!customerAccountCode.matches("TK-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid customer account code.");
        }
    }
}
