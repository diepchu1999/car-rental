package com.carrental.pricing.api;

import java.time.Instant;

/** Cổng snapshot báo giá cho booking theo BR-122 và ADR-0014; không giữ chỗ hoặc tính lại tiền. */
public interface QuoteDirectory {
    /**
     * Chỉ nhận mã khách từ danh tính đã xác thực của use case gọi vào, không từ body của khách.
     * Sai chủ/không có chủ/mã thiếu trả QUOTE_NOT_FOUND; đúng chủ nhưng at >= hạn trả QUOTE_EXPIRED.
     * Tham gia transaction của bên gọi, không tự mở giao dịch độc lập.
     */
    QuoteRef getForBooking(String code, String customerAccountCode, Instant at);
}
