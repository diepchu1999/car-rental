package com.carrental.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Bản sao một dòng tiền thuê từ báo giá; booking không phụ thuộc domain của pricing (ADR-0008/0014).
 * @param type loại dòng đã được pricing phân giải
 * @param dayType loại ngày ứng với mức tăng thực sự áp dụng
 * @param startAt mốc bắt đầu dòng
 * @param hours số giờ tính tiền
 * @param dailyRate giá ngày đã dùng
 * @param uplift mức tăng thập phân đã dùng
 * @param amount thành tiền đã làm tròn theo BR-236
 */
public record BookingPriceLine(String type, String dayType, Instant startAt, int hours,
        long dailyRate, BigDecimal uplift, long amount) {
    /** Kiểm hình dạng dòng, giữ nguyên số tiền nhận từ báo giá thay vì định giá lại. */
    public BookingPriceLine {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dayType, "dayType");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(uplift, "uplift");
        boolean validHours = switch (type) {
            case "DAY_BLOCK" -> hours == 24;
            case "HOURLY_PACKAGE" -> hours == 4;
            case "REMAINDER_HOURS", "OVERAGE_HOURS" -> hours >= 1 && hours <= 4;
            default -> false;
        };
        if (!validHours || !java.util.Set.of("WEEKDAY", "WEEKEND", "HOLIDAY").contains(dayType)
                || startAt.getNano() % 1_000 != 0 || dailyRate <= 0 || dailyRate % 1_000 != 0
                || uplift.signum() < 0 || (dayType.equals("WEEKDAY") && uplift.signum() != 0)
                || amount < 0 || amount % 1_000 != 0) {
            throw new IllegalArgumentException("Invalid booking price line.");
        }
    }
}
