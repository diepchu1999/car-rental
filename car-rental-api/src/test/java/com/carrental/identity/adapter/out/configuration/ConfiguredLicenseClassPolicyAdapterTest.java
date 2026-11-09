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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BR-128/ADR-0013: bind đủ mốc và hai bảng từ YAML thật; cấu hình sai chặn khởi động. */
class ConfiguredLicenseClassPolicyAdapterTest {
    private static final Instant AT = Instant.parse("2026-10-30T03:00:00Z");
    private static final String PREFIX = "car-rental.identity.license-classes";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(ConfiguredLicenseClassPolicyAdapterTest::removePersonalEnvironment)
            .withUserConfiguration(ConfiguredLicenseClassPolicyAdapter.class);

    /** Đối chiếu đúng hai cột BR-128, gồm tập hạng chính xác và quyền của xe 16 chỗ. */
    @ParameterizedTest @CsvSource({"true,B1,false", "true,B2,false", "true,C,false", "true,D,true", "true,E,true",
            "false,B,false", "false,C1,false", "false,C,false", "false,D1,true", "false,D2,true", "false,D,true"})
    void readsSeparateApprovedSeatMatricesFromYaml(boolean old, String code, boolean allowsSixteen) {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).run(context -> {
            assertStarted(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ReadLicenseClassPolicyPort.class).size());
            var policy = context.getBean(ReadLicenseClassPolicyPort.class).policyAt(AT);
            assertEquals(LocalDate.of(2025, 1, 1), policy.cutoverDate());
            var table = policy.tableFor(old ? LocalDate.of(2024, 12, 31) : LocalDate.of(2025, 1, 1));
            assertEquals(old ? Set.of("B1", "B2", "C", "D", "E") : Set.of("B", "C1", "C", "D1", "D2", "D"),
                    table.allowedSeatsByClass().keySet());
            for (int seats : new int[]{4, 5, 7}) assertTrue(table.allowsSeats(code, seats));
            assertEquals(allowsSixteen, table.allowsSeats(code, 16));
            assertFalse(table.allowsSeats(code, 6)); assertFalse(table.supportsClass("UNKNOWN"));
        });
    }

    /** Đổi một bảng không ghi đè bảng còn lại hoặc bị hợp nhất với dữ liệu Java. */
    @ParameterizedTest @ValueSource(strings = {"before-cutover", "from-cutover"})
    void acceptsAnIndependentReplacementTable(String regime) {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).withPropertyValues(
                PREFIX + "." + regime + "[0].code=LOCAL_TEST_CLASS", PREFIX + "." + regime + "[0].seats[0]=16")
                .run(context -> {
                    assertStarted(context.getStartupFailure());
                    var policy = context.getBean(ReadLicenseClassPolicyPort.class).policyAt(AT);
                    var replaced = regime.equals("before-cutover") ? policy.beforeCutover() : policy.fromCutover();
                    var unchanged = regime.equals("before-cutover") ? policy.fromCutover() : policy.beforeCutover();
                    assertEquals(Set.of("LOCAL_TEST_CLASS"), replaced.allowedSeatsByClass().keySet());
                    assertTrue(replaced.allowsSeats("LOCAL_TEST_CLASS", 16));
                    assertFalse(unchanged.supportsClass("LOCAL_TEST_CLASS"));
                    assertTrue(unchanged.allowsSeats("C", 7));
                });
    }

    /** Mốc chuyển đổi được bind thật; ngày 2025 không bị Java ghi đè cấu hình thử. */
    @Test void honorsConfiguredCutoverDate() {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml)
                .withPropertyValues(PREFIX + ".cutover-date=2028-06-01").run(context -> {
                    assertStarted(context.getStartupFailure());
                    var policy = context.getBean(ReadLicenseClassPolicyPort.class).policyAt(AT);
                    assertEquals(LocalDate.of(2028, 6, 1), policy.cutoverDate());
                    assertTrue(policy.tableFor(LocalDate.of(2028, 5, 31)).supportsClass("B1"));
                    assertFalse(policy.tableFor(LocalDate.of(2028, 6, 1)).supportsClass("B1"));
                });
    }

    /** Nguồn local chưa có lịch sử phiên bản cấu hình; mốc đánh giá khác với ngày cấp chọn bảng. */
    @Test void requiresTimestampButReturnsSameLocalSnapshot() {
        runner.withInitializer(ConfiguredLicenseClassPolicyAdapterTest::loadYaml).run(context -> {
            assertStarted(context.getStartupFailure()); var port = context.getBean(ReadLicenseClassPolicyPort.class);
            assertSame(port.policyAt(AT), port.policyAt(Instant.parse("2000-01-01T00:00:00Z")));
            assertSame(port.policyAt(AT), port.policyAt(Instant.parse("2100-01-01T00:00:00Z")));
            assertThrows(NullPointerException.class, () -> port.policyAt(null));
        });
    }

    /** Thiếu đúng một thành phần vẫn ngăn khởi động dù hai thành phần khác hợp lệ. */
    @ParameterizedTest @ValueSource(strings = {"cutover-date", "before-cutover", "from-cutover"})
    void missingComponentFailsStartup(String component) {
        var partial = Arrays.stream(validProperties()).filter(value -> !value.startsWith(PREFIX + "." + component))
                .toArray(String[]::new);
        runner.withPropertyValues(partial).run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Ngày không đúng ISO hoặc không có thật không được tự sửa thành ngày gần nhất. */
    @ParameterizedTest @ValueSource(strings = {"", "not-a-date", "01/01/2025", "2025-02-29", "2025-01-01T00:00:00Z"})
    void invalidCutoverDateFailsStartup(String value) {
        runner.withPropertyValues(validProperties()).withPropertyValues(PREFIX + ".cutover-date=" + value)
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Cả hai bảng đều kiểm số chỗ rỗng/sai kiểu/không dương; các phần còn lại luôn hợp lệ. */
    @ParameterizedTest @ValueSource(strings = {"before-cutover", "from-cutover"})
    void invalidSeatsFailStartup(String regime) {
        for (String value : new String[]{"", "abc", "0", "-1", "2147483648"}) {
            runner.withPropertyValues(validProperties())
                    .withPropertyValues(PREFIX + "." + regime + "[0].seats[0]=" + value)
                    .run(context -> assertConfigurationFailure(context.getStartupFailure()));
        }
    }

    /** Bỏ code hoặc số chỗ của một hàng không được chấp nhận thành bảng một phần. */
    @ParameterizedTest @ValueSource(strings = {"before-cutover", "from-cutover"})
    void incompleteEntryFailsStartup(String regime) {
        for (String field : new String[]{"code", "seats[0]"}) {
            var partial = Arrays.stream(validProperties())
                    .filter(value -> !value.startsWith(PREFIX + "." + regime + "[0]." + field + "="))
                    .toArray(String[]::new);
            runner.withPropertyValues(partial).run(context -> assertConfigurationFailure(context.getStartupFailure()));
        }
    }

    /** Hai hàng cùng hạng trong một bảng bị từ chối; cùng hạng giữa hai bảng là hợp lệ. */
    @ParameterizedTest @ValueSource(strings = {"before-cutover", "from-cutover"})
    void duplicateClassesFailStartup(String regime) {
        runner.withPropertyValues(validProperties()).withPropertyValues(PREFIX + "." + regime + "[1].code=LOCAL_TEST_CLASS",
                PREFIX + "." + regime + "[1].seats[0]=16")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Không tự bỏ phần tử số chỗ bị lặp ở bất kỳ bảng nào. */
    @ParameterizedTest @ValueSource(strings = {"before-cutover", "from-cutover"})
    void duplicateSeatsFailStartup(String regime) {
        runner.withPropertyValues(validProperties()).withPropertyValues(PREFIX + "." + regime + "[0].seats[1]=4")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    /** Cấu hình thử tối thiểu có đủ ba thành phần để test âm chỉ hỏng ở trường đang kiểm. */
    private static String[] validProperties() {
        return new String[]{PREFIX + ".cutover-date=2025-01-01", PREFIX + ".before-cutover[0].code=LOCAL_TEST_CLASS",
                PREFIX + ".before-cutover[0].seats[0]=4", PREFIX + ".from-cutover[0].code=LOCAL_TEST_CLASS",
                PREFIX + ".from-cutover[0].seats[0]=4"};
    }

    /** Giữ cause của lỗi bind để dễ phân biệt lỗi cấu hình với lỗi assertion. */
    private static void assertStarted(Throwable failure) {
        if (failure != null) throw new AssertionError("License class context failed to start.", failure);
    }

    /** Không chấp nhận lỗi bean bất kỳ thay cho lỗi cấu hình bảng hạng. */
    private static void assertConfigurationFailure(Throwable failure) {
        assertNotNull(failure);
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                    && cause.getMessage().contains(PREFIX)) return;
        }
        fail("Expected a license class configuration error.");
    }

    /** Loại nguồn cá nhân để bảng YAML được kiểm không bị biến shell hoặc system property thay thế. */
    private static void removePersonalEnvironment(ConfigurableApplicationContext context) {
        var sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }

    /** Đọc application.yml thật trên classpath, không sao chép dữ liệu production vào runner. */
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
