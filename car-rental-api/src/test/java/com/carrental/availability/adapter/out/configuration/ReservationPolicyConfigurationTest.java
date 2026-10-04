package com.carrental.availability.adapter.out.configuration;

import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm cấu hình thật và adapter chính sách theo BR-103, BR-225, ADR-0013.
 *
 * <p>Context chỉ quét cấu hình availability và adapter đọc chính sách,
 * không khởi động datasource, Flyway hoặc Docker. Đọc YAML thật và loại
 * cấu hình môi trường cá nhân để kết quả mặc định có thể tái lập.
 */
class ReservationPolicyConfigurationTest {

    private static final Instant AT = Instant.parse("2030-09-30T00:00:00Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ReservationPolicyConfigurationTest::loadApplicationProperties)
            .withUserConfiguration(ConfiguredReservationPolicyAdapter.class);

    /** Kiểm mặc định một giờ, ba mươi giây và đúng một implementation của port. */
    @Test
    void usesYamlDefaultsAndWiresOnePolicyAdapter() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ReadReservationPolicyPort.class).size());
            ReadReservationPolicyPort port = context.getBean(ReadReservationPolicyPort.class);
            assertEquals(Duration.ofHours(1), port.holdDuration(AT));
            assertSame(context.getBean("reservationHoldDuration", Duration.class), port.holdDuration(AT));
            assertEquals(Duration.ofSeconds(30), context.getBean("reservationHoldSweepInterval", Duration.class));
        });
    }

    /** Kiểm hai biến môi trường ánh xạ độc lập qua YAML, không bị đảo hoặc đóng cứng. */
    @Test
    void acceptsIndependentEnvironmentOverrides() {
        runner.withPropertyValues(
                "CAR_RENTAL_HOLD_DURATION=PT90M",
                "CAR_RENTAL_HOLD_SWEEP_INTERVAL=PT45S"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(90), context.getBean(ReadReservationPolicyPort.class).holdDuration(AT));
            assertEquals(Duration.ofSeconds(45), context.getBean("reservationHoldSweepInterval", Duration.class));
        });
    }

    /** Kiểm ghi đè trực tiếp thuộc tính ứng dụng và nhận giá trị dương nhỏ hơn một giây. */
    @Test
    void acceptsDirectPropertiesAndPositiveFractionalDurations() {
        runner.withPropertyValues(
                "car-rental.availability.hold-duration=PT0.5S",
                "car-rental.availability.hold-sweep-interval=PT0.25S"
        ).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMillis(500), context.getBean(ReadReservationPolicyPort.class).holdDuration(AT));
            assertEquals(Duration.ofMillis(250), context.getBean("reservationHoldSweepInterval", Duration.class));
        });
    }

    /**
     * Kiểm nguồn local trả cùng chính sách cho các mốc, không giả vờ có lịch sử hiệu lực.
     *
     * @param at mốc quá khứ hoặc tương lai truyền qua port
     */
    @ParameterizedTest
    @ValueSource(strings = {"2000-01-01T00:00:00Z", "2030-09-30T00:00:00Z", "2100-01-01T00:00:00Z"})
    void currentAdapterDoesNotSelectPolicyByTime(String at) {
        runner.withPropertyValues("CAR_RENTAL_HOLD_DURATION=PT90M").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofMinutes(90), context.getBean(ReadReservationPolicyPort.class)
                    .holdDuration(Instant.parse(at)));
        });
    }

    /** Kiểm bên gọi không được bỏ mốc thời gian dù nguồn local chưa phân giải theo mốc. */
    @Test
    void rejectsMissingPolicyTimestamp() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            ReadReservationPolicyPort port = context.getBean(ReadReservationPolicyPort.class);
            assertThrows(NullPointerException.class, () -> port.holdDuration(null));
        });
    }

    /**
     * Kiểm cả hai cấu hình sai làm khởi động thất bại, kể cả nhịp dọn chưa có scheduler sử dụng.
     *
     * @param environmentName tên biến được YAML đọc
     * @param propertyName tên thuộc tính cần xuất hiện trong lỗi
     * @param value giá trị sai, không được thay bằng mặc định
     */
    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsInvalidValuesAtStartup(String environmentName, String propertyName, String value) {
        runner.withPropertyValues(environmentName + "=" + value).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(hasConfigurationError(failure, propertyName),
                    "Expected a configuration error naming " + propertyName);
        });
    }

    /** Cung cấp cấu hình rỗng, không dương, sai định dạng và vượt giới hạn cho từng thuộc tính. */
    private static Stream<Arguments> invalidDurations() {
        return Stream.of(
                new String[]{"CAR_RENTAL_HOLD_DURATION", "car-rental.availability.hold-duration"},
                new String[]{"CAR_RENTAL_HOLD_SWEEP_INTERVAL", "car-rental.availability.hold-sweep-interval"}
        ).flatMap(names -> Stream.of("", "PT0S", "PT-1S", "not-a-duration", "30", "PT9223372036854775808S")
                .map(value -> Arguments.of(names[0], names[1], value)));
    }

    /** Tìm nguyên nhân cấu hình có tên thuộc tính, không phụ thuộc thông báo bọc ngoài của Spring. */
    private static boolean hasConfigurationError(Throwable failure, String propertyName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException
                    && current.getMessage() != null
                    && current.getMessage().contains(propertyName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nạp YAML và quét riêng cấu hình availability, không kéo datasource vào context.
     * Biến môi trường hoặc JVM cá nhân không được tác động vào kết quả test.
     */
    private static void loadApplicationProperties(ConfigurableApplicationContext context) {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                    "application-yaml-for-reservation-policy-test", new ClassPathResource("application.yml")
            )) {
                sources.addLast(source);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to load application.yml for reservation policy tests.", failure);
        }
        new ClassPathBeanDefinitionScanner((BeanDefinitionRegistry) context)
                .scan("com.carrental.availability.config");
    }
}
