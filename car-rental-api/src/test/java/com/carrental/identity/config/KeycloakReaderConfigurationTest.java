package com.carrental.identity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm cấu hình YAML thật và fail-fast, không đọc môi trường cá nhân, không dựng DB hay gọi Keycloak. */
class KeycloakReaderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    for (var source : new YamlPropertySourceLoader().load("identity-test-yaml",
                            new ClassPathResource("application.yml"))) {
                        sources.addLast(source);
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).withUserConfiguration(KeycloakReaderConfiguration.class);

    /** Bốn biến được nối đúng từ YAML tới settings; dấu / cuối URL được chuẩn hoá, secret giữ nguyên. */
    @Test
    void bindsActualYamlToReaderSettings() {
        configured().run(context -> {
            assertNull(context.getStartupFailure());
            var settings = context.getBean(KeycloakReaderSettings.class);
            assertEquals("http://keycloak.invalid:8080", settings.baseUrl());
            assertEquals("car-rental", settings.realm());
            assertEquals("identity-reader", settings.readerClientId());
            assertEquals("test-only-secret+&", settings.readerSecret());
        });
    }

    /** Không âm thầm dùng credential/realm mặc định khi môi trường chưa được khai báo. */
    @Test
    void refusesMissingEnvironment() {
        runner.run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** Từng biến bắt buộc rỗng đều phải làm startup thất bại, không chờ đến lần gán chi nhánh đầu tiên. */
    @ParameterizedTest
    @ValueSource(strings = {"CAR_RENTAL_KEYCLOAK_BASE_URL", "CAR_RENTAL_KEYCLOAK_REALM",
            "CAR_RENTAL_KEYCLOAK_READER_CLIENT_ID", "CAR_RENTAL_KEYCLOAK_READER_SECRET"})
    void refusesEachEmptySetting(String variable) {
        configured().withPropertyValues(variable + "=").run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** URL chỉ có HTTP(S) và host, không chứa userinfo, query hay fragment có thể làm lộ credential. */
    @ParameterizedTest
    @ValueSource(strings = {"not a url", "/relative", "ftp://keycloak.invalid", "http:///missing-host",
            "http://user:secret@keycloak.invalid", "http://keycloak.invalid?token=secret",
            "http://keycloak.invalid#secret"})
    void rejectsUnsafeOrMalformedBaseUrl(String value) {
        configured().withPropertyValues("CAR_RENTAL_KEYCLOAK_BASE_URL=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** Realm/client không thể tự chèn segment đường dẫn hoặc ký tự điều khiển. */
    @ParameterizedTest
    @CsvSource({"CAR_RENTAL_KEYCLOAK_REALM,../master", "CAR_RENTAL_KEYCLOAK_REALM,..",
            "CAR_RENTAL_KEYCLOAK_REALM,car rental", "CAR_RENTAL_KEYCLOAK_READER_CLIENT_ID,reader/other"})
    void rejectsInvalidNames(String variable, String value) {
        configured().withPropertyValues(variable + "=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    /** Thiếu/rỗng/control character hoặc placeholder secret đều bị chặn mà không in giá trị ra exception. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "${MISSING_SECRET}", "private\r\nsecret"})
    void rejectsInvalidSecretWithoutEchoingValue(String secret) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new KeycloakReaderSettings("http://keycloak.invalid", "car-rental", "identity-reader", secret));
        assertEquals("Invalid car-rental.identity.keycloak.reader-secret.", failure.getMessage());
        assertNull(failure.getCause());
    }

    /** Không log secret qua toString; HTTPS và context path của Keycloak vẫn được hỗ trợ. */
    @Test
    void redactsSettingsAndSupportsContextPath() {
        var settings = new KeycloakReaderSettings("https://keycloak.invalid/auth/", "car-rental", "identity-reader",
                "test-only-secret+&");
        assertEquals("https://keycloak.invalid/auth", settings.baseUrl());
        assertEquals("KeycloakReaderSettings[redacted]", settings.toString());
    }

    /** Toàn bộ giá trị là dữ liệu giả, không đòi hỏi .env hoặc realm local tồn tại. */
    private ApplicationContextRunner configured() {
        return runner.withPropertyValues("CAR_RENTAL_KEYCLOAK_BASE_URL=http://keycloak.invalid:8080/",
                "CAR_RENTAL_KEYCLOAK_REALM=car-rental", "CAR_RENTAL_KEYCLOAK_READER_CLIENT_ID=identity-reader",
                "CAR_RENTAL_KEYCLOAK_READER_SECRET=test-only-secret+&");
    }
}
