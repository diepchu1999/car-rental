package com.carrental.keycloak.authentication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.keycloak.common.util.Environment;

/** BR-120: văn bản thử dùng riêng ở local; phiên bản và nội dung phải khớp màn hình khách đã đọc. */
record LocalTermsDocument(String version, String text) {
    /** Không cho chạy với phiên bản trống hoặc văn bản không được đánh dấu là bản thử. */
    LocalTermsDocument {
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("CAR_RENTAL_TERMS_VERSION must contain 1-80 safe version characters.");
        }
        if (text == null || !text.startsWith("ĐIỀU KHOẢN THỬ — CHỈ DÙNG LOCAL")) {
            throw new IllegalArgumentException("Local terms must be explicitly marked as test-only.");
        }
    }

    /** Nạp văn bản UTF-8 đã đóng trong JAR, không lấy nội dung điều khoản từ request. */
    static LocalTermsDocument load(String version) {
        try (var input = LocalTermsDocument.class.getResourceAsStream("/terms/local-test-terms.txt")) {
            if (input == null) throw new IllegalStateException("Local terms resource is missing.");
            return new LocalTermsDocument(version, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load local terms.", failure);
        }
    }

    /** Bản thử không được dùng để lấy đồng ý điều khoản trên server chạy chế độ thường. */
    void requireLocalRuntime() {
        if (!Environment.isDevMode()) throw new IllegalStateException("Local test terms require Keycloak start-dev.");
    }

    /** Đổi cả nội dung lẫn phiên bản trong khi khách đang đọc đều phải hiện lại văn bản mới. */
    String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((version + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }
}
