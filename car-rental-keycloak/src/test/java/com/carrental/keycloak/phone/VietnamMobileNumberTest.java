package com.carrental.keycloak.phone;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** BR-120/129: một dạng E.164 cho số di động Việt Nam, không nhận số cố định/quốc tế. */
class VietnamMobileNumberTest {
    /** Các cách viết phổ biến quy về cùng một tài khoản. */
    @ParameterizedTest @ValueSource(strings = {"0912345678", "+84912345678", "0912 345 678", "(0912) 345-678"})
    void normalizesEquivalentNumbers(String input) {
        assertEquals("+84912345678", VietnamMobileNumber.normalize(input));
    }

    /** Từ chối cả ký tự điều khiển và không đưa đầu vào nhạy cảm vào lỗi. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"02838221234", "+14155552671", "12345", "0912345678 ext 1", "0912345678\nFORGED", "abcdef"})
    void rejectsNonVietnamMobileNumbers(String input) {
        var error = assertThrows(IllegalArgumentException.class, () -> VietnamMobileNumber.normalize(input));
        if (input != null && !input.isEmpty()) assertFalse(error.getMessage().contains(input));
    }
}
