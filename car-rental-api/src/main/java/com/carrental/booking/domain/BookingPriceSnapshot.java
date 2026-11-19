package com.carrental.booking.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Giá và tham số bảo hiểm đã dùng, không chứa lịch lễ hay giá trị quyết toán chưa có (ADR-0014).
 * @param dailyRate giá ngày tại lúc báo giá
 * @param lines bản sao các dòng tiền thuê theo đúng thứ tự
 * @param insuranceCalculation cách tính bảo hiểm của báo giá
 * @param insuranceValue mức cố định hoặc tỷ lệ đã dùng, phải lớn hơn 0 (BR-231)
 * @param insuranceBillingDays số ngày tính bảo hiểm đã phân giải
 * @param amounts tổng, cọc và phần còn lại đã đóng băng
 */
public record BookingPriceSnapshot(long dailyRate, List<BookingPriceLine> lines,
        String insuranceCalculation, BigDecimal insuranceValue, int insuranceBillingDays, BookingAmounts amounts) {
    /** Không cho thay danh sách từ bên ngoài hoặc lưu tổng không khớp các dòng. */
    public BookingPriceSnapshot {
        lines = List.copyOf(lines);
        Objects.requireNonNull(insuranceCalculation, "insuranceCalculation");
        Objects.requireNonNull(insuranceValue, "insuranceValue");
        Objects.requireNonNull(amounts, "amounts");
        long sum = 0;
        for (var line : lines) sum = Math.addExact(sum, line.amount());
        if (dailyRate <= 0 || dailyRate % 1_000 != 0 || lines.isEmpty()
                || lines.stream().anyMatch(line -> line.dailyRate() != dailyRate)
                || sum != amounts.rentalAmount() || insuranceBillingDays <= 0 || insuranceValue.signum() <= 0
                || !java.util.Set.of("PER_BILLING_DAY", "PERCENT_OF_RENT").contains(insuranceCalculation)
                || (insuranceCalculation.equals("PER_BILLING_DAY") && insuranceValue.stripTrailingZeros().scale() > 0)) {
            throw new IllegalArgumentException("Invalid booking price snapshot.");
        }
    }
}
