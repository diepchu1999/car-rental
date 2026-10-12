package com.carrental.pricing.domain;

import java.util.List;

/**
 * Kết quả tính thuần, chưa có mã báo giá và chưa ghi CSDL.
 * @param lines các dòng tiền thuê, không bao gồm bảo hiểm BR-231
 * @param rentalAmount tổng dòng tiền thuê đã làm tròn BR-236
 * @param insuranceFee một dòng bảo hiểm đã làm tròn
 * @param totalAmount tiền thuê cộng bảo hiểm
 */
public record CalculatedPrice(List<QuoteLine> lines, long rentalAmount, long insuranceFee, long totalAmount) {
    /** Sao chép danh sách và xác nhận tổng khớp các dòng; không cho phép tổng bị tràn. */
    public CalculatedPrice {
        lines = List.copyOf(lines);
        long sum = 0;
        for (var line : lines) sum = Math.addExact(sum, line.amount());
        if (lines.isEmpty() || sum != rentalAmount || insuranceFee < 0
                || insuranceFee % 1_000 != 0 || Math.addExact(rentalAmount, insuranceFee) != totalAmount) {
            throw new IllegalArgumentException("Calculated totals do not match the price lines.");
        }
    }
}
