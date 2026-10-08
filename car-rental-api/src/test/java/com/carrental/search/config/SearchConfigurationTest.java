package com.carrental.search.config;

import com.carrental.search.domain.SearchRadiusSettings;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm YAML thật và fail-fast bán kính BR-125, không khởi động datasource hoặc container. */
class SearchConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    for (var source : new YamlPropertySourceLoader().load("search-test-yaml",
                            new ClassPathResource("application.yml"))) {
                        sources.addLast(source);
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).withUserConfiguration(SearchConfiguration.class);

    /** YAML cấp mặc định 10 km, trần 30 km; 31 km trả lỗi chứ không bị cắt. */
    @Test
    void loadsDefaultsAndRejectsRadiusAboveMaximum() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var settings = context.getBean(SearchRadiusSettings.class);
            assertEquals(10.0, settings.resolve(null));
            assertEquals(30.0, settings.resolve(30.0));
            assertEquals(ErrorCode.INVALID_REQUEST, assertThrowsExactly(DomainException.class,
                    () -> settings.resolve(31.0)).errorCode());
        });
    }

    /** Giá trị cấu hình thay đổi thật sự đi tới settings, không bị Java thay bằng mặc định. */
    @Test
    void appliesConfigurationOverrides() {
        runner.withPropertyValues("car-rental.search.default-radius-km=5",
                "car-rental.search.max-radius-km=20").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new SearchRadiusSettings(5, 20), context.getBean(SearchRadiusSettings.class));
        });
    }

    /** Zero, âm, NaN/vô cực, mặc định lớn hơn trần hoặc giá trị không phải số phải chặn startup. */
    @ParameterizedTest
    @CsvSource({"0,30", "-1,30", "10,0", "31,30", "NaN,30", "10,Infinity", "bad,30"})
    void rejectsInvalidConfiguration(String defaultValue, String maximum) {
        runner.withPropertyValues("car-rental.search.default-radius-km=" + defaultValue,
                "car-rental.search.max-radius-km=" + maximum).run(context ->
                assertNotNull(context.getStartupFailure()));
    }
}
