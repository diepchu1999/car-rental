package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * Bộ tham số giá tại một mốc; application lấy qua port theo ADR-0013.
 * @param weekendDays tập ngày cuối tuần BR-225
 * @param weekendUplift tỷ lệ tăng cuối tuần, 0.2 nghĩa là 20%
 * @param holidayUplift tỷ lệ tăng lễ tết, không cộng dồn với cuối tuần
 * @param holidayCalendar dữ liệu lịch, không sao chép toàn bộ vào mỗi báo giá
 * @param insurance bảo hiểm bắt buộc khai báo BR-231
 * @param quoteValidity hạn dùng báo giá BR-122
 */
public record PricingParameters(Set<DayOfWeek> weekendDays, BigDecimal weekendUplift,
        BigDecimal holidayUplift, HolidayCalendar holidayCalendar,
        InsuranceTerms insurance, Duration quoteValidity) {
    /** Giữ bộ tham số bất biến; không tự thay cấu hình sai bằng giá trị mặc định. */
    public PricingParameters {
        weekendDays = Set.copyOf(weekendDays);
        if (weekendDays.isEmpty()) throw new IllegalArgumentException("Weekend days must not be empty.");
        Objects.requireNonNull(weekendUplift, "weekendUplift");
        Objects.requireNonNull(holidayUplift, "holidayUplift");
        if (weekendUplift.signum() < 0 || holidayUplift.signum() < 0) {
            throw new IllegalArgumentException("Price uplifts must not be negative.");
        }
        Objects.requireNonNull(holidayCalendar, "holidayCalendar");
        Objects.requireNonNull(insurance, "insurance");
        Objects.requireNonNull(quoteValidity, "quoteValidity");
        if (quoteValidity.isNegative() || quoteValidity.isZero()) {
            throw new IllegalArgumentException("Quote validity must be greater than zero.");
        }
    }

    /** Chọn mức cao hơn khi lễ trùng cuối tuần; bằng nhau chọn HOLIDAY theo BR-218. */
    public DayPricing dayAt(Instant at, ZoneId zone) {
        var date = Objects.requireNonNull(at, "at").atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
        boolean weekend = weekendDays.contains(date.getDayOfWeek());
        if (holidayCalendar.dates().contains(date)) {
            if (weekend && weekendUplift.compareTo(holidayUplift) > 0) {
                return new DayPricing(DayType.WEEKEND, weekendUplift);
            }
            return new DayPricing(DayType.HOLIDAY, holidayUplift);
        }
        return weekend ? new DayPricing(DayType.WEEKEND, weekendUplift)
                : new DayPricing(DayType.WEEKDAY, BigDecimal.ZERO);
    }

    /** Loại ngày và mức tăng đã chọn; lưu trên từng dòng để giải thích số tiền. */
    public record DayPricing(DayType dayType, BigDecimal uplift) {
        /** Không cho một mốc đã phân loại mang giá trị null. */
        public DayPricing {
            Objects.requireNonNull(dayType, "dayType");
            Objects.requireNonNull(uplift, "uplift");
        }
    }
}
