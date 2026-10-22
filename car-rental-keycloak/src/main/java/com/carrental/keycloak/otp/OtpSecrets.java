package com.carrental.keycloak.otp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Sinh mã ngẫu nhiên và HMAC có phân miền; không lưu mã, số hoặc IP bản rõ trong bảng OTP. */
public final class OtpSecrets {
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    /** Secret base64 tối thiểu 32 byte, chỉ đến từ môi trường triển khai. */
    public OtpSecrets(String encodedSecret) {
        try {
            secret = Base64.getDecoder().decode(encodedSecret == null ? "" : encodedSecret);
            if (secret.length < 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("CAR_RENTAL_OTP_HMAC_SECRET must be base64 with at least 32 bytes.");
        }
    }

    /** Giữ cả số 0 đầu; SecureRandom.nextInt không dùng phép chia dư gây lệch phân bố. */
    public String generate(int digits) {
        if (digits < 1 || digits > 9) throw new IllegalArgumentException("Unsupported OTP length.");
        StringBuilder result = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) result.append(random.nextInt(10));
        return result.toString();
    }

    /** Ghép có độ dài, tránh hai bộ đầu vào khác nhau tạo cùng chuỗi ký. */
    public String digest(String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", failure);
        }
    }

    /** So sánh hai HMAC cùng độ dài bằng hàm chống rò thời gian. */
    public boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
