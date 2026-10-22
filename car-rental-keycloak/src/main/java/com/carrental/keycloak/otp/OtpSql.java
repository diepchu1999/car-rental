package com.carrental.keycloak.otp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tải SQL có namespace riêng một lần; không đưa JDBC hoặc file SQL sang backend. */
final class OtpSql {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    /** Lớp chỉ chứa bộ đọc tài nguyên. */
    private OtpSql() { }
    /** Báo lỗi rõ khi JAR thiếu SQL thay vì trả chuỗi rỗng. */
    static String get(String name) {
        return CACHE.computeIfAbsent(name, key -> {
            try (var input = OtpSql.class.getResourceAsStream("/sql/car-rental-otp/" + key + ".sql")) {
                if (input == null) throw new IllegalStateException("Missing OTP SQL resource: " + key);
                String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                if (sql.isBlank()) throw new IllegalStateException("Empty OTP SQL resource: " + key);
                return sql;
            } catch (IOException failure) { throw new IllegalStateException("Cannot load OTP SQL: " + key, failure); }
        });
    }
}
