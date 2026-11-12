package com.carrental.booking.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/** Đầu vào tạo đơn chỉ có báo giá và key; không nhận chủ, giá hoặc TTL do khách tự khai (BR-122). */
public record CreateBookingCommand(String quoteCode, String idempotencyKey) {
    /** Kiểm định dạng trước truy vấn; giữ nguyên key để không gộp hai giá trị khác nhau. */
    public CreateBookingCommand {
        quoteCode = Validations.requiredText(quoteCode, "quoteCode");
        idempotencyKey = Validations.requiredText(idempotencyKey, "idempotencyKey");
        if (!quoteCode.matches("BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("quoteCode must be a valid quote business code.");
        }
    }

    /** Nhận giá trị thô; REST adapter sẽ truyền header key vào đây. */
    public static CreateBookingCommand from(String quoteCode, String idempotencyKey) {
        return new CreateBookingCommand(quoteCode, idempotencyKey);
    }
}
