package com.carrental.booking.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Chuyển kiểu JDBC nội bộ booking mà không làm mất độ chính xác của bản sao điều kiện. */
final class BookingJdbcValues {
    /** Tiện ích không có instance. */
    private BookingJdbcValues() { }

    /** Timestamptz độc lập timezone của JVM/kết nối. */
    static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    /** Lưu Duration bằng giây thập phân, giữ cả nano giây, không dùng double. */
    static BigDecimal seconds(Duration value) {
        return BigDecimal.valueOf(value.getSeconds()).add(BigDecimal.valueOf(value.getNano(), 9));
    }

    /** Khôi phục nguyên số giây và phần lẻ; dữ liệu không biểu diễn được phải lỗi rõ. */
    static Duration duration(BigDecimal value) {
        long whole = value.toBigInteger().longValueExact();
        int nanos = value.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValueExact();
        return Duration.ofSeconds(whole, nanos);
    }
}
