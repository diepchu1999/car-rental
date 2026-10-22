package com.carrental.keycloak.otp;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Tham số vận hành BR-129; giá trị khởi điểm nằm ở cấu hình, không nằm trong thuật toán. */
public record OtpPolicy(int digits, Duration lifetime, int maximumFailures, Duration resendDelay,
                        int sendsPerPhoneHour, int sendsPerPhoneDay, int sendsPerIpHour,
                        int failuresPerIpHour) {
    /** Kiểm cả giới hạn kỹ thuật để cấu hình sai không làm tràn số hoặc sinh mã quá yếu ngoài ý muốn. */
    public OtpPolicy {
        if (digits < 1 || digits > 9 || lifetime == null || lifetime.isZero() || lifetime.isNegative()
                || resendDelay == null || resendDelay.isZero() || resendDelay.isNegative()
                || maximumFailures < 1 || sendsPerPhoneHour < 1 || sendsPerPhoneDay < 1
                || sendsPerIpHour < 1 || failuresPerIpHour < 1) {
            throw new IllegalArgumentException("OTP policy values must be positive; digits must be between 1 and 9.");
        }
    }

    /** Đọc đủ mọi biến bắt buộc; không dùng mặc định khi thiếu hoặc sai cấu hình. */
    public static OtpPolicy from(Function<String, String> values) {
        return new OtpPolicy(integer(values, "CAR_RENTAL_OTP_DIGITS"),
                duration(values, "CAR_RENTAL_OTP_LIFETIME"), integer(values, "CAR_RENTAL_OTP_MAX_FAILURES"),
                duration(values, "CAR_RENTAL_OTP_RESEND_DELAY"), integer(values, "CAR_RENTAL_OTP_PHONE_HOURLY_LIMIT"),
                integer(values, "CAR_RENTAL_OTP_PHONE_DAILY_LIMIT"), integer(values, "CAR_RENTAL_OTP_IP_SEND_HOURLY_LIMIT"),
                integer(values, "CAR_RENTAL_OTP_IP_FAILURE_HOURLY_LIMIT"));
    }

    /** Đầu vào Map tiện cho test, dùng đúng đường đọc cấu hình của runtime. */
    public static OtpPolicy from(Map<String, String> values) { return from(values::get); }

    /** Báo đúng tên biến thiếu, không log giá trị cấu hình. */
    private static String required(Function<String, String> values, String key) {
        String value = values.apply(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required.");
        return value;
    }

    /** Số đếm phải nguyên dương; exception chỉ nêu tên biến. */
    private static int integer(Function<String, String> values, String key) {
        try {
            int result = Integer.parseInt(required(values, key));
            if (result <= 0) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(key + " must be a positive integer.");
        }
    }

    /** Khoảng cấu hình phải dương và biểu diễn được bằng mili giây của JDBC. */
    private static Duration duration(Function<String, String> values, String key) {
        try {
            Duration result = Duration.parse(required(values, key));
            if (result.toMillis() <= 0 || result.toNanosPart() % 1_000_000 != 0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(key + " must be a positive duration with millisecond precision.");
        }
    }
}
