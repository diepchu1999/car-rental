package com.carrental.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Kiểm cấu hình đồng hồ dùng chung theo ADR-0004,
 * mục làm rõ ngày 21/09/2026.
 *
 * <p>Đọc application.yml thật để kiểm giá trị mặc định
 * và ánh xạ từ CAR_RENTAL_TIME_ZONE.
 *
 * <p>Chỉ đăng ký cấu hình đồng hồ, không khởi động ứng dụng,
 * datasource, Flyway hoặc container.
 */
class TimeConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withInitializer(
                            TimeConfigurationTest::loadApplicationProperties
                    )
                    .withUserConfiguration(TimeConfiguration.class);

    /**
     * Chứng minh giá trị mặc định trong YAML tạo đúng một đồng hồ
     * theo múi giờ Việt Nam và bean được dùng lại.
     */
    @Test
    void defaultsToVietnamTimeZoneWithOneSharedClock() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());

            Map<String, Clock> clocks = context.getBeansOfType(Clock.class);

            assertEquals(1, clocks.size());

            Clock clock = context.getBean(Clock.class);

            assertEquals(
                    ZoneId.of("Asia/Ho_Chi_Minh"),
                    clock.getZone()
            );

            assertSame(clock, clocks.get("applicationClock"));
            assertSame(clock, context.getBean(Clock.class));
        });
    }

    /**
     * Chứng minh biến cấu hình được YAML chuyển tới bean Clock,
     * không bị Java thay bằng múi giờ viết cứng.
     *
     * @param timeZone múi giờ thay thế được cung cấp cho test
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "UTC",
            "Asia/Tokyo"
    })
    void usesConfiguredTimeZone(String timeZone) {
        contextRunner
                .withPropertyValues(
                        "CAR_RENTAL_TIME_ZONE=" + timeZone
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());

                    assertEquals(
                            timeZone,
                            context.getEnvironment()
                                    .getProperty("car-rental.time-zone")
                    );

                    assertEquals(
                            ZoneId.of(timeZone),
                            context.getBean(Clock.class).getZone()
                    );

                    assertEquals(
                            1,
                            context.getBeansOfType(Clock.class).size()
                    );
                });
    }

    /**
     * Chứng minh cấu hình sai hoặc rỗng khiến tạo bean thất bại.
     *
     * <p>Giá trị mặc định chỉ áp dụng khi không có biến.
     * Biến đã có nhưng sai không được âm thầm thay bằng mặc định.
     *
     * @param timeZone định danh múi giờ không hợp lệ
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Invalid/Time_Zone",
            ""
    })
    void rejectsInvalidTimeZone(String timeZone) {
        contextRunner
                .withPropertyValues(
                        "CAR_RENTAL_TIME_ZONE=" + timeZone
                )
                .run(context -> {
                    Throwable failure = context.getStartupFailure();

                    assertNotNull(failure);

                    assertInstanceOf(
                            DateTimeException.class,
                            NestedExceptionUtils.getMostSpecificCause(failure)
                    );
                });
    }

    /**
     * Nạp YAML thật vào môi trường riêng của context kiểm thử.
     *
     * <p>Loại nguồn biến môi trường và thuộc tính JVM khỏi context này
     * để cấu hình cá nhân không làm sai kết quả kiểm mặc định.
     * Không sửa biến môi trường hệ điều hành hoặc System properties.
     *
     * <p>Các giá trị do withPropertyValues cung cấp vẫn được giữ
     * và có độ ưu tiên cao hơn YAML.
     *
     * @param context context chưa được khởi tạo bean
     * @throws UncheckedIOException nếu không đọc được application.yml
     */
    private static void loadApplicationProperties(
            ConfigurableApplicationContext context
    ) {
        MutablePropertySources sources =
                context.getEnvironment().getPropertySources();

        sources.remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        );
        sources.remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
        );

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

        try {
            for (PropertySource<?> source : loader.load(
                    "application-yaml-for-clock-test",
                    new ClassPathResource("application.yml")
            )) {
                sources.addLast(source);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to load application.yml for clock configuration tests.",
                    exception
            );
        }
    }
}