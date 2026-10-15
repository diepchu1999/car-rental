package com.carrental.keycloak.phone;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** BR-120/129: chỉ số di động Việt Nam, một dạng E.164 cho mọi cách nhập được chấp nhận. */
public final class VietnamMobileNumber {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    /** Không tạo đối tượng vì chuẩn hoá không giữ trạng thái. */
    private VietnamMobileNumber() { }

    /** Chuẩn hoá số nội địa hoặc +84; từ chối số bàn, quốc tế, extension và ký tự điều khiển. */
    public static String normalize(String input) {
        if (input == null || input.length() > 64 || !input.matches("[+0-9 ().-]+")) {
            throw invalid();
        }
        try {
            var number = PHONES.parse(input, "VN");
            if (number.hasExtension() || number.getCountryCode() != 84
                    || !PHONES.isValidNumberForRegion(number, "VN")
                    || PHONES.getNumberType(number) != PhoneNumberUtil.PhoneNumberType.MOBILE) {
                throw invalid();
            }
            return PHONES.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException failure) {
            throw invalid();
        }
    }

    /** Không đưa số điện thoại đầu vào vào exception/log. */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("A valid Vietnamese mobile phone number is required.");
    }
}
