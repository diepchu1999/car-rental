package com.carrental.booking.application.query;

import java.time.Instant;

/** Khóa trang giảm dần theo mốc tạo và mã nghiệp vụ, không dùng khóa chính số. */
public record BookingPagePosition(Instant createdAt, String code) {
    /** Giữ độ chính xác micro giây của CSDL và chặn mốc vượt phạm vi ngày API hỗ trợ. */
    public BookingPagePosition {
        if (createdAt == null || createdAt.getNano() % 1_000 != 0
                || createdAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || createdAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))
                || code == null || !code.matches("DT-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid booking page position.");
        }
    }
}
