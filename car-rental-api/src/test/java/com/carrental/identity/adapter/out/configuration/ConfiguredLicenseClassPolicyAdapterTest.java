package com.carrental.identity.adapter.out.configuration;

import com.carrental.identity.application.port.out.ReadLicenseClassPolicyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BR-128/ADR-0013: bind YAML thật và kiểm lỗi khởi động; không dựng datasource hay dùng môi trường cá nhân. */
class ConfiguredLicenseClassPolicyAdapterTest {
    private static final Instant AT = Instant.parse("2026-10-30T03:00:00Z");
    private static final String PREFIX = "car-rental.identity.license-classes";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ConfiguredLicenseClassPolicyAdapterTest::removePersonalEnvironment)
            .withUserConfiguration(ConfiguredLicenseClassPolicyAdapter.class);

    /** Hợp nhất đúng chín hạng ở BR-128; B/C không được tự suy lên xe 16 chỗ. */
    @ParameterizedTest @CsvSource({"B,false", "B1,false", "B2,false", "C1,false", "C,false",
            "D1,true", "D2,true", "D,true", "E,true"})
    void readsApprovedSeatMatrixFromYaml(String code, boolean allowsSixteen) {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).run(context -> {
            assertStarted(context.getStartupFailure()); assertEquals(1, context.getBeansOfType(ReadLicenseClassPolicyPort.class).size());
            var table = context.getBean(ReadLicenseClassPolicyPort.class).tableAt(AT);
            assertEquals(Set.of("B", "B1", "B2", "C1", "C", "D1", "D2", "D", "E"), table.allowedSeatsByClass().keySet());
            assertTrue(table.supportsClass(code));
            for (int seats : new int[]{4, 5, 7}) assertTrue(table.allowsSeats(code, seats));
            assertEquals(allowsSixteen, table.allowsSeats(code, 16));
            assertFalse(table.allowsSeats(code, 6)); assertFalse(table.supportsClass("UNKNOWN"));
        });
    }

    /** Đổi dữ liệu bằng bảng test mới làm đổi kết quả, không bị bảng cố định trong Java ghi đè. */
    @Test void acceptsAReplacementTableWithoutChangingJava() {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).withPropertyValues(
                PREFIX + "[0].code=LOCAL_TEST_CLASS", PREFIX + "[0].seats[0]=16")
                .run(context -> {
                    assertStarted(context.getStartupFailure()); var table = context.getBean(ReadLicenseClassPolicyPort.class).tableAt(AT);
                    assertEquals(Set.of("LOCAL_TEST_CLASS"), table.allowedSeatsByClass().keySet());
                    assertTrue(table.allowsSeats("LOCAL_TEST_CLASS", 16)); assertFalse(table.supportsClass("B"));
                });
    }

    /** Nguồn local không giả có lịch sử hiệu lực; vẫn yêu cầu mốc để thay adapter sau này. */
    @Test void requiresTimestampButReturnsSameLocalSnapshot() {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).run(context -> {
            assertStarted(context.getStartupFailure()); var port = context.getBean(ReadLicenseClassPolicyPort.class);
            assertSame(port.tableAt(AT), port.tableAt(Instant.parse("2000-01-01T00:00:00Z")));
            assertSame(port.tableAt(AT), port.tableAt(Instant.parse("2100-01-01T00:00:00Z")));
            assertThrows(NullPointerException.class, () -> port.tableAt(null));
        });
    }

    /** Không nạp YAML thì thiếu bảng phải ngăn khởi động, không âm thầm dùng một bảng Java. */
    @Test void missingTableFailsStartup() { runner.run(context -> assertConfigurationFailure(context.getStartupFailure())); }

    /** Số chỗ rỗng, không phải số hoặc không dương bị chặn ngay lúc bind/validate. */
    @ParameterizedTest @ValueSource(strings = {"", "abc", "0", "-1", "2147483648"})
    void invalidSeatsFailStartup(String raw) {
        runner.withPropertyValues(PREFIX + "[0].code=B", PREFIX + "[0].seats[0]=" + raw)
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Thiếu code hoặc thiếu tập số chỗ đều không tạo được bảng một phần. */
    @ParameterizedTest @ValueSource(strings = {"code=B", "seats[0]=4"})
    void incompleteEntryFailsStartup(String property) {
        runner.withPropertyValues(PREFIX + "[0]." + property)
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Hai hàng cùng hạng không được ghi đè lẫn nhau theo thứ tự cấu hình. */
    @Test void duplicateClassesFailStartup() {
        runner.withPropertyValues(PREFIX + "[0].code=B", PREFIX + "[0].seats[0]=4",
                PREFIX + "[1].code=B", PREFIX + "[1].seats[0]=16")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Một hàng lặp số chỗ là lỗi khai báo, không tự sửa mất dấu lỗi ở file cấu hình. */
    @Test void duplicateSeatsFailStartup() {
        runner.withPropertyValues(PREFIX + "[0].code=B", PREFIX + "[0].seats[0]=4", PREFIX + "[0].seats[1]=4")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Giữ cause để lỗi bind trong context thử không bị che bởi assertion chỉ in tên bean. */
    private static void assertStarted(Throwable failure) {
        if (failure != null) throw new AssertionError("License class context failed to start.", failure);
    }

    /** Chỉ chấp nhận lỗi cấu hình có tên property; lỗi bean bất kỳ không được tính là đạt. */
    private static void assertConfigurationFailure(Throwable failure) {
        assertNotNull(failure);
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                    && cause.getMessage().contains(PREFIX)) return;
        }
        fail("Expected a license class configuration error.");
    }

    /** Không để system properties hoặc biến shell cá nhân thay đổi bảng đang kiểm. */
    private static void removePersonalEnvironment(ConfigurableApplicationContext context) {
        var sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }

    /** Đọc đúng application.yml trên classpath để bắt cả lỗi sửa bảng dữ liệu đã được duyệt. */
    private static void loadYaml(ConfigurableApplicationContext context) {
        try {
            for (var source : new YamlPropertySourceLoader().load("license-class-yaml", new ClassPathResource("application.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load application.yml for license class tests.", failure);
        }
    }
}
