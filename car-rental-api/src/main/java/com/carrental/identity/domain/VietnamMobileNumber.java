package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/**
 * Số di động Việt Nam chuẩn E.164 theo BR-120/129, dùng cùng thuật toán và metadata với Keycloak.
 * Đây là phép kiểm dữ liệu, không phải bằng chứng sở hữu số; OTP thuộc Keycloak.
 *
 * @param value số nội địa hoặc quốc tế được chuẩn hoá ngay khi tạo
 */
public record VietnamMobileNumber(String value) {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    /**
     * Chuẩn hoá và từ chối số bàn, quốc tế, extension hoặc ký tự điều khiển như provider Keycloak.
     * Giới hạn 64 ký tự là hàng rào kỹ thuật đầu vào giống provider, không phải độ dài số lưu.
     * Biên JWT phải chuyển lỗi claim không hợp lệ thành lỗi xác thực, không trả dữ liệu token.
     */
    public VietnamMobileNumber {
        if (value == null || value.length() > 64 || !value.matches("[+0-9 ().-]+")) {
            throw invalid();
        }
        try {
            var number = PHONES.parse(value, "VN");
            if (number.hasExtension() || number.getCountryCode() != 84
                    || !PHONES.isValidNumberForRegion(number, "VN")
                    || PHONES.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.MOBILE) {
                throw invalid();
            }
            value = PHONES.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException failure) {
            throw invalid();
        }
    }

    /** Không lặp lại số điện thoại hoặc nguyên nhân parser trong thông báo công khai hay log. */
    private static DomainException invalid() {
        return DomainException.invalidInput("A valid Vietnamese mobile phone number is required.");
    }

    /** Tránh record tự in số điện thoại khi đối tượng vô tình được đưa vào log. */
    @Override
    public String toString() {
        return "VietnamMobileNumber[redacted]";
    }
}
