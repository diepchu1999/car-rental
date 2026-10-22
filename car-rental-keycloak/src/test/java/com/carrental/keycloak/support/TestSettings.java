package com.carrental.keycloak.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Đọc cấu hình test được commit và image từ .env.example, không đọc secret trong .env. */
public final class TestSettings {
    /** Không tạo instance tiện ích. */
    private TestSettings() { }

    /** Tìm tài nguyên repo được commit để container test dùng đúng realm như local, không tạo bản sao. */
    public static Path repositoryFile(String relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Missing repository file: " + relative);
    }

    /** Trả bản sao sửa được để test fail-fast từng tham số. */
    public static Map<String, String> otp() {
        var properties = new Properties();
        try (var input = TestSettings.class.getResourceAsStream("/otp-test.properties")) {
            if (input == null) throw new IllegalStateException("Missing OTP test settings.");
            properties.load(input);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        var result = new HashMap<String, String>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return result;
    }

    /** Đi ngược cây thư mục như backend, báo thiếu biến rõ ràng thay vì dùng image mặc định. */
    public static String repositorySetting(String key) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path example = directory.resolve(".env.example");
            if (!Files.isRegularFile(example)) continue;
            try {
                return Files.readAllLines(example).stream().map(String::trim)
                        .filter(line -> line.startsWith(key + "="))
                        .map(line -> line.substring(key.length() + 1).trim()).filter(value -> !value.isEmpty())
                        .findFirst().orElseThrow(() -> new IllegalStateException("Missing " + key + " in .env.example"));
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        throw new IllegalStateException("Cannot locate repository .env.example.");
    }
}
