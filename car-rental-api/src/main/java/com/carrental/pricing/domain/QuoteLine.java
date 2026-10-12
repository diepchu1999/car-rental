package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Một dòng tiền thuê đã làm tròn theo BR-236, giữ giá và mức tăng đã dùng (ADR-0014).
 * @param type loại dòng theo glossary
 * @param dayType nhãn mức tăng đã áp
 * @param startAt mốc bắt đầu khối/phần giờ lẻ
 * @param hours số giờ tính tiền của dòng
 * @param dailyRate giá ngày của xe, nguyên VND
 * @param uplift mức tăng thập phân, không phải phần trăm dạng 20
 * @param amount tiền của riêng dòng đã làm tròn
 */
public record QuoteLine(QuoteLineType type, DayType dayType, Instant startAt, int hours,
        long dailyRate, BigDecimal uplift, long amount) {
    /** Bảo vệ cấu trúc dòng tiền bất biến, không tự tính lại số tiền khi đọc. */
    public QuoteLine {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dayType, "dayType");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(uplift, "uplift");
        if (hours <= 0 || dailyRate <= 0 || dailyRate % 1_000 != 0
                || uplift.signum() < 0 || amount < 0 || amount % 1_000 != 0) {
            throw new IllegalArgumentException("Invalid quote line values.");
        }
    }
}
