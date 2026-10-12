package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/** Dữ liệu thử tường minh, không phải giá bảo hiểm mặc định; chỉ trên test classpath. */
public final class PricingTestFixtures {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Tiện ích fixture không có trạng thái thay đổi. */
    private PricingTestFixtures() { }

    /** Chuyển giờ trong ví dụ nghiệp vụ sang Instant, không phụ thuộc timezone của JVM. */
    public static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant();
    }

    /** Mức tăng khởi điểm đã duyệt; bảo hiểm 100000 chỉ là dữ liệu thử. */
    public static PricingParameters parameters() {
        return new PricingParameters(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                new BigDecimal("0.2"), new BigDecimal("0.5"),
                new HolidayCalendar(LocalDate.of(2027, 12, 31), Set.of(
                        LocalDate.of(2026, 11, 24), LocalDate.of(2027, 2, 4),
                        LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 6))),
                new InsuranceTerms(InsuranceCalculation.PER_BILLING_DAY, new BigDecimal("100000")),
                Duration.ofMinutes(15));
    }
}
