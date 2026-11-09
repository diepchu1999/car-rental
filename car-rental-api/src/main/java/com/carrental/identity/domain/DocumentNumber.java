package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;

import java.nio.charset.StandardCharsets;

/**
 * Số giấy tờ dạng rõ trong bộ nhớ theo BR-130; không dùng trực tiếp làm tham số SQL hay nội dung log.
 * Chỉ bỏ khoảng trắng hai đầu, không bỏ số 0 đầu, dấu phân cách hoặc đổi hoa/thường khi chưa có BR.
 *
 * @param value số khách nhập; kết quả chuẩn hoá được dùng cho cả mã hoá và băm
 */
public record DocumentNumber(String value) {
    /** Kiểm trường bắt buộc và dữ liệu văn bản an toàn; không tự đặt quy tắc độ dài CCCD/GPLX. */
    public DocumentNumber {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw DomainException.invalidInput("A document number without control characters is required.");
        }
        value = value.strip();
    }

    /** Chặn record tự in số rõ khi vô tình được nối vào thông báo hoặc log. */
    @Override
    public String toString() {
        return "DocumentNumber[redacted]";
    }
}
