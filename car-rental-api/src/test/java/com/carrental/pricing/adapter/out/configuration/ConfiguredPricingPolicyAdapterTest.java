package com.carrental.pricing.adapter.out.configuration;

import com.carrental.pricing.application.port.out.ReadPricingPolicyPort;
import com.carrental.pricing.domain.InsuranceCalculation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm YAML thật, ánh xạ env và startup thất bại khi cấu hình sai, không dùng Docker/CSDL. */
class ConfiguredPricingPolicyAdapterTest {
    private static final Instant AT = Instant.parse("2026-10-12T00:00:00Z");
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    for (var source : new YamlPropertySourceLoader().load("pricing-test-yaml",
                            new ClassPathResource("application.yml"))) {
                        sources.addLast(source);
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).withUserConfiguration(ConfiguredPricingPolicyAdapter.class);

    /** Dữ liệu bảo hiểm thử chỉ cấp cho context test, không ghi vào cấu hình production. */
    private ApplicationContextRunner valid() {
        return runner.withPropertyValues("CAR_RENTAL_INSURANCE_CALCULATION=PER_BILLING_DAY",
                "CAR_RENTAL_INSURANCE_VALUE=100000");
    }

    /** Khởi điểm BR-225 và toàn bộ 23 ngày lễ phải đúng danh sách Task 7 đã duyệt. */
    @Test
    void loadsApprovedYamlDefaultsAndCompleteHolidayCalendar() {
        valid().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ReadPricingPolicyPort.class).size());
            var parameters = context.getBean(ReadPricingPolicyPort.class).parametersAt(AT);
            assertEquals(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), parameters.weekendDays());
            assertEquals(0, new BigDecimal("0.2").compareTo(parameters.weekendUplift()));
            assertEquals(0, new BigDecimal("0.5").compareTo(parameters.holidayUplift()));
            assertEquals(Duration.ofMinutes(15), parameters.quoteValidity());
            assertEquals(InsuranceCalculation.PER_BILLING_DAY, parameters.insurance().calculation());
            assertEquals(new BigDecimal("100000"), parameters.insurance().value());
            assertEquals(LocalDate.of(2027, 12, 31), parameters.holidayCalendar().declaredThrough());
            assertEquals(approvedHolidays(), parameters.holidayCalendar().dates());
        });
    }

    /** Không có fallback bảo hiểm; thiếu từng biến làm startup thất bại và nêu đúng property. */
    @ParameterizedTest
    @CsvSource({"CAR_RENTAL_INSURANCE_VALUE=100000,insurance.calculation",
            "CAR_RENTAL_INSURANCE_CALCULATION=PER_BILLING_DAY,insurance.value"})
    void rejectsMissingInsuranceConfiguration(String presentVariable, String missingProperty) {
        runner.withPropertyValues(presentVariable).run(context ->
                assertConfigurationFailure(context.getStartupFailure(), missingProperty));
    }

    /** Ghi đè env đi thật tới port, gồm tỷ lệ trên 100%, cuối tuần khác và hạn dùng khác. */
    @Test
    void appliesEnvironmentOverridesWithoutArtificialRateCeilings() {
        valid().withPropertyValues("CAR_RENTAL_PRICING_WEEKEND_DAYS=FRIDAY",
                "CAR_RENTAL_PRICING_WEEKEND_UPLIFT=1.1", "CAR_RENTAL_PRICING_HOLIDAY_UPLIFT=2.5",
                "CAR_RENTAL_QUOTE_VALIDITY=PT30M", "CAR_RENTAL_HOLIDAY_DECLARED_THROUGH=2028-12-31",
                "CAR_RENTAL_INSURANCE_CALCULATION=PERCENT_OF_RENT", "CAR_RENTAL_INSURANCE_VALUE=2.2")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var parameters = context.getBean(ReadPricingPolicyPort.class).parametersAt(AT);
                    assertEquals(Set.of(DayOfWeek.FRIDAY), parameters.weekendDays());
                    assertEquals(new BigDecimal("1.1"), parameters.weekendUplift());
                    assertEquals(new BigDecimal("2.5"), parameters.holidayUplift());
                    assertEquals(Duration.ofMinutes(30), parameters.quoteValidity());
                    assertEquals(LocalDate.of(2028, 12, 31), parameters.holidayCalendar().declaredThrough());
                    assertEquals(InsuranceCalculation.PERCENT_OF_RENT, parameters.insurance().calculation());
                    assertEquals(new BigDecimal("2.2"), parameters.insurance().value());
                });
    }

    /** Mức tăng bằng không hợp lệ theo hợp đồng >=0; thời lượng dương nhỏ vẫn không bị thay mặc định. */
    @Test
    void acceptsZeroUpliftsAndPositiveFractionalValidity() {
        valid().withPropertyValues("car-rental.pricing.weekend-uplift=0", "car-rental.pricing.holiday-uplift=0",
                "car-rental.pricing.quote-validity=PT0.5S").run(context -> {
            assertNull(context.getStartupFailure());
            var parameters = context.getBean(ReadPricingPolicyPort.class).parametersAt(AT);
            assertEquals(0, parameters.weekendUplift().signum());
            assertEquals(0, parameters.holidayUplift().signum());
            assertEquals(Duration.ofMillis(500), parameters.quoteValidity());
        });
    }

    /** Kiểm cấu hình sai ở mọi nhóm, không chỉ bảo hiểm; thông báo phải nêu đúng property cần sửa. */
    @ParameterizedTest
    @MethodSource("invalidProperties")
    void rejectsInvalidConfigurationAtStartup(String property, String value) {
        valid().withPropertyValues("car-rental.pricing." + property + "=" + value).run(context ->
                assertConfigurationFailure(context.getStartupFailure(), property));
    }

    /** Nguồn hiện tại không giả vờ có lịch sử; vẫn yêu cầu at để sau này thay adapter ADR-0013. */
    @Test
    void currentAdapterKeepsSnapshotAndRequiresTimestamp() {
        valid().run(context -> {
            assertNull(context.getStartupFailure());
            var port = context.getBean(ReadPricingPolicyPort.class);
            assertSame(port.parametersAt(AT), port.parametersAt(Instant.parse("2000-01-01T00:00:00Z")));
            assertSame(port.parametersAt(AT), port.parametersAt(Instant.parse("2100-01-01T00:00:00Z")));
            assertThrows(NullPointerException.class, () -> port.parametersAt(null));
        });
    }

    /** Tập trường hợp âm/rỗng/sai định dạng, cố định không nguyên, ngày lễ vượt hạn và duration tràn. */
    private static Stream<Arguments> invalidProperties() {
        return Stream.of(
                Arguments.of("insurance.calculation", ""), Arguments.of("insurance.calculation", "UNKNOWN"),
                Arguments.of("insurance.value", ""), Arguments.of("insurance.value", "0"),
                Arguments.of("insurance.value", "-100000"), Arguments.of("insurance.value", "100000.1"),
                Arguments.of("insurance.value", "NaN"), Arguments.of("weekend-days", ""),
                Arguments.of("weekend-days", "SATURDAY,UNKNOWN"), Arguments.of("weekend-days", "SATURDAY,"),
                Arguments.of("weekend-uplift", "-0.1"), Arguments.of("weekend-uplift", "bad"),
                Arguments.of("holiday-uplift", "-1"), Arguments.of("holiday-uplift", "NaN"),
                Arguments.of("quote-validity", "PT0S"), Arguments.of("quote-validity", "PT-1S"),
                Arguments.of("quote-validity", "bad"), Arguments.of("quote-validity", "PT9223372036854775808S"),
                Arguments.of("holiday-calendar.declared-through", "bad"),
                Arguments.of("holiday-calendar.declared-through", "2027-11-23"),
                Arguments.of("holiday-calendar.dates[0]", "bad"),
                Arguments.of("holiday-calendar.dates[0]", "2028-01-01"));
    }

    /** So sánh độc lập với YAML, giữ đủ mọi ngày cuối tuần trong kỳ nghỉ chính thức đã duyệt. */
    private static Set<LocalDate> approvedHolidays() {
        return Stream.of("2026-11-24", "2027-01-01", "2027-01-02", "2027-01-03",
                "2027-02-04", "2027-02-05", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-02-10",
                "2027-04-16", "2027-04-17", "2027-04-18", "2027-04-30", "2027-05-01", "2027-05-02", "2027-05-03",
                "2027-09-02", "2027-09-03", "2027-09-04", "2027-09-05", "2027-11-24")
                .map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }

    /** Không phụ thuộc lớp bọc lỗi của Spring; tìm tên property trong chuỗi cause của lỗi cấu hình. */
    private static void assertConfigurationFailure(Throwable failure, String property) {
        assertNotNull(failure, "Expected startup failure for " + property);
        String expected = "car-rental.pricing." + property;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expected)) return;
        }
        fail("Expected a startup failure naming " + expected, failure);
    }
}
