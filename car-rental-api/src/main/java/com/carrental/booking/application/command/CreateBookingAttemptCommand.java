package com.carrental.booking.application.command;

import com.carrental.booking.domain.BookingPriceSnapshot;
import com.carrental.booking.domain.BookingRentalTerms;
import com.carrental.pricing.api.QuoteRef;
import com.carrental.vehicle.api.VehicleRef;
import java.time.Instant;
import java.util.Objects;

/**
 * Dữ liệu nội bộ đã kiểm qua các cổng, dùng lại khi chỉ mã DT bị trùng; không phải request của khách.
 * @param code mã của lần thử này
 * @param quote báo giá đã kiểm đúng chủ/còn hạn
 * @param vehicle xe ACTIVE đã tra, chỉ mang thuộc tính cần đặt xe
 * @param price tiền đã đóng băng, không tính lại khi sinh mã khác
 * @param terms điều kiện đã kiểm cho giờ nhận/trả thực tế
 * @param createdAt mốc bắt đầu tạo đơn, giữ nguyên qua các lần thử mã
 */
public record CreateBookingAttemptCommand(String code, QuoteRef quote, VehicleRef vehicle,
        BookingPriceSnapshot price, BookingRentalTerms terms, Instant createdAt) {
    /** Chặn dữ liệu nội bộ thiếu trước khi có thể giữ chỗ. */
    public CreateBookingAttemptCommand {
        Objects.requireNonNull(code, "code"); Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(vehicle, "vehicle"); Objects.requireNonNull(price, "price");
        Objects.requireNonNull(terms, "terms"); Objects.requireNonNull(createdAt, "createdAt");
    }
}
