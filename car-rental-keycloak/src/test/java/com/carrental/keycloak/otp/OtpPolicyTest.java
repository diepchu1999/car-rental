package com.carrental.keycloak.otp;

import com.carrental.keycloak.support.TestSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** BR-129: tham số đến từ cấu hình, thiếu/sai thì thất bại thay vì chọn mặc định. */
class OtpPolicyTest {
    /** Toàn bộ giá trị khởi điểm đúng bảng BR-129, gồm giới hạn sai theo IP. */
    @Test void readsEveryApprovedValue() {
        var policy = OtpPolicy.from(TestSettings.otp());
        assertEquals(new OtpPolicy(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5, 10, 20, 100), policy);
    }

    /** Xoá bất kỳ biến bắt buộc nào cũng không được lùi về mặc định. */
    @Test void rejectsEveryMissingSetting() {
        for (String key : TestSettings.otp().keySet()) {
            if (key.endsWith("HMAC_SECRET")) continue;
            var values = TestSettings.otp(); values.remove(key);
            assertThrows(IllegalArgumentException.class, () -> OtpPolicy.from(values), key);
        }
    }

    /** Số đếm phải nguyên dương và nằm trong giới hạn kiểu dữ liệu. */
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "2147483648", "invalid"})
    void rejectsInvalidCounters(String value) {
        var values = TestSettings.otp(); values.put("CAR_RENTAL_OTP_IP_FAILURE_HOURLY_LIMIT", value);
        assertThrows(IllegalArgumentException.class, () -> OtpPolicy.from(values));
    }

    /** Khoảng thời gian sai, âm, bằng không hay nhỏ hơn độ chính xác hỗ trợ đều bị chặn. */
    @ParameterizedTest @ValueSource(strings = {"PT0S", "PT-1S", "PT0.000001S", "five minutes"})
    void rejectsInvalidDurations(String value) {
        var values = TestSettings.otp(); values.put("CAR_RENTAL_OTP_LIFETIME", value);
        assertThrows(IllegalArgumentException.class, () -> OtpPolicy.from(values));
    }

    /** Thay tham số ở file/env phải thật sự thay hành vi policy, không chỉ đọc cho có. */
    @Test void honorsConfiguredValues() {
        var values = TestSettings.otp(); values.put("CAR_RENTAL_OTP_MAX_FAILURES", "3");
        values.put("CAR_RENTAL_OTP_DIGITS", "8");
        assertEquals(3, OtpPolicy.from(values).maximumFailures());
        assertEquals(8, OtpPolicy.from(values).digits());
        values.put("CAR_RENTAL_OTP_DIGITS", "10");
        assertThrows(IllegalArgumentException.class, () -> OtpPolicy.from(values));
    }
}
