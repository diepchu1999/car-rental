package com.carrental.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Base64;

/**
 * Lắp khoá giấy tờ từ môi trường; fail-fast khi thiếu/sai và không để binder in rejectedValue là secret.
 * Cặp khoá và phiên bản phải giữ nguyên qua restart; xoay vòng khoá là công việc riêng chưa triển khai.
 */
@Configuration(proxyBeanMethods = false)
class DocumentCryptoConfiguration {
    private static final String PREFIX = "car-rental.identity.documents.crypto.";

    /** Đọc trong biên bắt lỗi để cả lỗi placeholder/Base64 cũng không giữ cause có giá trị nhạy cảm. */
    @Bean
    DocumentCryptoKeys documentCryptoKeys(Environment environment) {
        byte[] encryption = null;
        byte[] hashing = null;
        try {
            int version = Integer.parseInt(environment.getRequiredProperty(PREFIX + "key-version"));
            encryption = decode(environment.getRequiredProperty(PREFIX + "encryption-key"));
            hashing = decode(environment.getRequiredProperty(PREFIX + "hash-key"));
            return new DocumentCryptoKeys(version, encryption, hashing);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid car-rental.identity.documents.crypto configuration: "
                    + "key-version must be positive; encryption-key and hash-key must be distinct Base64 32-byte keys.");
        } finally {
            if (encryption != null) Arrays.fill(encryption, (byte) 0);
            if (hashing != null) Arrays.fill(hashing, (byte) 0);
        }
    }

    /** Chỉ nhận Base64 chuẩn, đúng 32 byte; không chấp nhận khoảng trắng, placeholder hoặc chuỗi mật khẩu. */
    private static byte[] decode(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException("Invalid document key encoding.");
        }
        return decoded;
    }
}
