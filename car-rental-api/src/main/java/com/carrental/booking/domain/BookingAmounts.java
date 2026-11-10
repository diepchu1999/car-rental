package com.carrental.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Các khoản tiền đã đóng băng của đơn, không phải giao dịch thu tiền (BR-201/236, ADR-0014).
 * Cọc tính trên tổng gồm bảo hiểm; phần còn lại luôn bằng tổng trừ cọc.
 * @param rentalAmount tiền thuê đã cộng các dòng làm tròn
 * @param insuranceFee phí bảo hiểm đã làm tròn
 * @param totalAmount tổng báo giá, nguyên VND
 * @param depositRatio tỷ lệ đã được policy phân giải, dạng 0.3 = 30%
 * @param deposit cọc đã làm tròn tới 1.000 VND
 * @param balance phần còn lại, không phải thế chấp
 */
public record BookingAmounts(long rentalAmount, long insuranceFee, long totalAmount,
        BigDecimal depositRatio, long deposit, long balance) {
    /** Kiểm số đã lưu, không lấy tỷ lệ mới để tính lại đơn cũ. */
    public BookingAmounts {
        Objects.requireNonNull(depositRatio, "depositRatio");
        if (rentalAmount <= 0 || insuranceFee < 0 || totalAmount <= 0
                || rentalAmount % 1_000 != 0 || insuranceFee % 1_000 != 0
                || Math.addExact(rentalAmount, insuranceFee) != totalAmount
                || depositRatio.signum() <= 0 || depositRatio.compareTo(BigDecimal.ONE) > 0
                || deposit != roundedDeposit(totalAmount, depositRatio)
                || balance < 0 || Math.addExact(deposit, balance) != totalAmount) {
            throw new IllegalArgumentException("Invalid booking amounts.");
        }
    }

    /** BR-201: dùng tổng gồm bảo hiểm; không tính lại tiền thuê hoặc phí bảo hiểm. */
    public static BookingAmounts calculate(long rentalAmount, long insuranceFee, BigDecimal depositRatio) {
        long total = Math.addExact(rentalAmount, insuranceFee);
        long deposit = roundedDeposit(total, Objects.requireNonNull(depositRatio, "depositRatio"));
        return new BookingAmounts(rentalAmount, insuranceFee, total, depositRatio, deposit,
                Math.subtractExact(total, deposit));
    }

    /** BR-236: số thập phân chính xác, từ 500 VND làm tròn lên; không dùng double. */
    private static long roundedDeposit(long total, BigDecimal ratio) {
        return BigDecimal.valueOf(total).multiply(ratio).setScale(-3, RoundingMode.HALF_UP).longValueExact();
    }
}
