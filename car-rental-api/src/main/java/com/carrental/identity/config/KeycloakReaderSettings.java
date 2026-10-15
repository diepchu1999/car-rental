package com.carrental.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** Cấu hình client chỉ đọc của ADR-0006; kiểm ngay lúc khởi động và không in secret qua toString. */
@ConfigurationProperties("car-rental.identity.keycloak")
public record KeycloakReaderSettings(String baseUrl, String realm, String readerClientId, String readerSecret) {
    /** Không chấp nhận cấu hình thiếu, placeholder chưa thay, URL có credential/query hoặc tên sai định dạng. */
    public KeycloakReaderSettings {
        requireValue(baseUrl, "base-url");
        requireValue(realm, "realm");
        requireValue(readerClientId, "reader-client-id");
        requireValue(readerSecret, "reader-secret");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException failure) {
            throw invalid("base-url");
        }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid("base-url");
        }
        if (!realm.matches("[A-Za-z0-9_.-]+") || realm.equals(".") || realm.equals("..")) {
            throw invalid("realm");
        }
        if (!readerClientId.matches("[A-Za-z0-9_.-]+")) {
            throw invalid("reader-client-id");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** Chỉ nêu tên thuộc tính bị lỗi, không nối giá trị cấu hình vào exception. */
    private static void requireValue(String value, String property) {
        if (value == null || value.isBlank() || value.contains("${")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(property);
        }
    }

    /** Tạo thông báo fail-fast không mang giá trị secret hoặc URL có thông tin nhạy cảm. */
    private static IllegalArgumentException invalid(String property) {
        return new IllegalArgumentException("Invalid car-rental.identity.keycloak." + property + ".");
    }

    /** Không cho log ngẫu nhiên một record cấu hình làm lộ credential. */
    @Override
    public String toString() {
        return "KeycloakReaderSettings[redacted]";
    }
}
