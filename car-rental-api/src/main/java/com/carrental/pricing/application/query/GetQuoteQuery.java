package com.carrental.pricing.application.query;

import com.carrental.shared.validation.Validations;

/** Mã báo giá dùng trên URL, không cắt khoảng trắng hoặc ép định dạng mã lúc tra cứu. */
public record GetQuoteQuery(String code) {
    /** Mã thiếu/trắng là đầu vào sai; mã có nội dung nhưng không tồn tại được use case trả 404. */
    public GetQuoteQuery { code = Validations.requiredText(code, "code"); }

    /** Nhận mã thô từ biên HTTP hoặc module nội bộ. */
    public static GetQuoteQuery from(String code) { return new GetQuoteQuery(code); }
}
