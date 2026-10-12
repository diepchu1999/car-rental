package com.carrental.pricing.adapter.out.configuration;

import com.carrental.pricing.application.port.out.ReadPricingPolicyPort;
import com.carrental.pricing.domain.HolidayCalendar;
import com.carrental.pricing.domain.InsuranceCalculation;
import com.carrental.pricing.domain.InsuranceTerms;
import com.carrental.pricing.domain.PricingParameters;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Nguồn cấu hình tạm cho BR-218/225/231 và ADR-0013.
 * Chưa đáp ứng admin chỉnh và lịch sử hiệu lực; đổi giá trị cần khởi động lại,
 * đổi danh sách lễ trong YAML cần build/phát hành lại. Phải thay bằng module config trước khách thật.
 * Mốc at được kiểm nhưng chưa dùng chọn phiên bản; báo giá sẽ giữ bản sao giá trị đã dùng (ADR-0014).
 */
@Component
class ConfiguredPricingPolicyAdapter implements ReadPricingPolicyPort {
    private static final String PREFIX = "car-rental.pricing.";
    private final PricingParameters parameters;

    /** Nạp và kiểm toàn bộ cấu hình ngay lúc tạo bean, không chờ request báo giá đầu tiên. */
    ConfiguredPricingPolicyAdapter(Environment environment) {
        var weekendDays = new HashSet<DayOfWeek>();
        for (String day : required(environment, "weekend-days").split(",", -1)) {
            try {
                weekendDays.add(DayOfWeek.valueOf(day.strip()));
            } catch (IllegalArgumentException failure) {
                throw invalid("weekend-days", failure);
            }
        }
        BigDecimal weekend = decimal(environment, "weekend-uplift", false);
        BigDecimal holiday = decimal(environment, "holiday-uplift", false);
        InsuranceCalculation calculation;
        try {
            calculation = InsuranceCalculation.valueOf(required(environment, "insurance.calculation"));
        } catch (IllegalArgumentException failure) {
            throw invalid("insurance.calculation", failure);
        }
        BigDecimal value = decimal(environment, "insurance.value", true);
        InsuranceTerms insurance;
        try {
            insurance = new InsuranceTerms(calculation, value);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw invalid("insurance.value", failure);
        }
        Duration validity;
        try {
            validity = Duration.parse(required(environment, "quote-validity"));
            if (validity.isZero() || validity.isNegative()) throw new IllegalArgumentException("Nonpositive duration.");
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw invalid("quote-validity", failure);
        }
        LocalDate declaredThrough = date(required(environment, "holiday-calendar.declared-through"),
                "holiday-calendar.declared-through");
        List<String> rawDates;
        try {
            rawDates = Binder.get(environment).bind(PREFIX + "holiday-calendar.dates",
                    Bindable.listOf(String.class)).orElse(List.of());
        } catch (RuntimeException failure) {
            throw invalid("holiday-calendar.dates", failure);
        }
        var dates = new HashSet<LocalDate>();
        for (int index = 0; index < rawDates.size(); index++) {
            String property = "holiday-calendar.dates[" + index + "]";
            var day = date(rawDates.get(index), property);
            if (day.isAfter(declaredThrough)) {
                throw invalid(property, new IllegalArgumentException(
                        "Date exceeds " + PREFIX + "holiday-calendar.declared-through."));
            }
            dates.add(day);
        }
        parameters = new PricingParameters(weekendDays, weekend, holiday,
                new HolidayCalendar(declaredThrough, dates), insurance, validity);
    }

    /** Trả cùng bộ tham số bất biến cho mọi mốc ở nguồn local hiện tại, không tự đọc Clock. */
    @Override
    public PricingParameters parametersAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return parameters;
    }

    /** Thiếu/rỗng hoặc placeholder chưa khai báo phải báo đúng tên property, không lộ giá trị đầu vào. */
    private static String required(Environment environment, String name) {
        String value;
        try {
            value = environment.getProperty(PREFIX + name);
        } catch (IllegalArgumentException failure) {
            throw invalid(name, failure);
        }
        if (value == null || value.isBlank()) {
            throw invalid(name, new IllegalArgumentException("Required configuration is missing."));
        }
        return value.strip();
    }

    /** Đọc tỷ lệ chính xác; bảo hiểm > 0, mức tăng >= 0, không đặt trần tỷ lệ. */
    private static BigDecimal decimal(Environment environment, String name, boolean strictlyPositive) {
        try {
            var value = new BigDecimal(required(environment, name));
            if (value.signum() < 0 || (strictlyPositive && value.signum() == 0)) {
                throw new IllegalArgumentException("Invalid sign.");
            }
            return value;
        } catch (IllegalArgumentException failure) {
            throw invalid(name, failure);
        }
    }

    /** Ngày lịch phải là ISO yyyy-MM-dd; lỗi thuộc cấu hình chứ không phải request của khách. */
    private static LocalDate date(String raw, String name) {
        try {
            return LocalDate.parse(raw);
        } catch (java.time.DateTimeException failure) {
            throw invalid(name, failure);
        }
    }

    /** Giữ nguyên cause và tên property để lỗi khởi động có thể chẩn đoán được. */
    private static IllegalArgumentException invalid(String name, Throwable cause) {
        return new IllegalArgumentException("Invalid configuration: " + PREFIX + name, cause);
    }
}
