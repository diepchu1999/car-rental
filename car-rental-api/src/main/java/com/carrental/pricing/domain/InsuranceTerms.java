package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Chính sách bảo hiểm đã khai báo, không có giá mặc định (BR-231).
 * @param calculation cách tính phí
 * @param value số nguyên VND hoặc tỷ lệ thập phân, lớn hơn không
 */
public record InsuranceTerms(InsuranceCalculation calculation, BigDecimal value) {
    /** Chặn cấu hình thiếu, phí không dương và phí cố định có phần lẻ. */
    public InsuranceTerms {
        Objects.requireNonNull(calculation, "insurance calculation");
        Objects.requireNonNull(value, "insurance value");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Insurance value must be greater than zero.");
        }
        if (calculation == InsuranceCalculation.PER_BILLING_DAY) {
            value.toBigIntegerExact();
        }
    }

    /** Tính một dòng bảo hiểm rồi làm tròn riêng theo BR-231/236, không giới hạn tỷ lệ ở 100%. */
    public long fee(long rentalAmount, int billingDays) {
        if (rentalAmount < 0 || billingDays <= 0) {
            throw new IllegalArgumentException("Rental amount must be nonnegative and billing days positive.");
        }
        BigDecimal amount = switch (calculation) {
            case PER_BILLING_DAY -> value.multiply(BigDecimal.valueOf(billingDays));
            case PERCENT_OF_RENT -> value.multiply(BigDecimal.valueOf(rentalAmount));
        };
        return VndRounding.round(amount);
    }
}
