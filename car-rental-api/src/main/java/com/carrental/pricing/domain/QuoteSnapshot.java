package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Các tham số đã dùng cho một báo giá (ADR-0014); không giữ toàn bộ lịch lễ (ADR-0013).
 * @param dailyRate giá ngày của đúng xe tại lúc tính
 * @param weekendDays tập ngày cuối tuần đã dùng
 * @param weekendUplift mức tăng cuối tuần đã dùng
 * @param holidayUplift mức tăng lễ đã dùng
 * @param insurance cách tính và giá trị bảo hiểm đã dùng
 * @param insuranceBillingDays số ngày tính bảo hiểm đã phân giải, gói giờ luôn một (BR-231)
 * @param quoteValidity hạn dùng đã đọc tại lúc tạo
 */
public record QuoteSnapshot(long dailyRate, Set<DayOfWeek> weekendDays, BigDecimal weekendUplift,
        BigDecimal holidayUplift, InsuranceTerms insurance, int insuranceBillingDays, Duration quoteValidity) {
    /** Bảo vệ cấu trúc snapshot, không dùng nguồn cấu hình mới khi khôi phục. */
    public QuoteSnapshot {
        weekendDays = Set.copyOf(weekendDays);
        Objects.requireNonNull(weekendUplift, "weekendUplift");
        Objects.requireNonNull(holidayUplift, "holidayUplift");
        Objects.requireNonNull(insurance, "insurance");
        Objects.requireNonNull(quoteValidity, "quoteValidity");
        if (dailyRate <= 0 || dailyRate % 1_000 != 0 || weekendDays.isEmpty()
                || weekendUplift.signum() < 0 || holidayUplift.signum() < 0
                || insuranceBillingDays <= 0 || quoteValidity.isZero() || quoteValidity.isNegative()) {
            throw new IllegalArgumentException("Invalid quote snapshot.");
        }
    }

    /** Chỉ sao chép giá trị ảnh hưởng báo giá, bỏ dữ liệu lịch đã được giải thành loại ngày trên dòng. */
    public static QuoteSnapshot from(long dailyRate, PricingParameters parameters, int insuranceBillingDays) {
        Objects.requireNonNull(parameters, "parameters");
        return new QuoteSnapshot(dailyRate, parameters.weekendDays(), parameters.weekendUplift(),
                parameters.holidayUplift(), parameters.insurance(), insuranceBillingDays, parameters.quoteValidity());
    }
}
