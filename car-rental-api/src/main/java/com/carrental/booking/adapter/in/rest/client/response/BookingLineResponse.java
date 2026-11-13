package com.carrental.booking.adapter.in.rest.client.response;

import com.carrental.booking.domain.BookingPriceLine;
import java.math.BigDecimal;
import java.time.Instant;

/** Dòng tiền thuê đã đóng băng, không phải row JDBC hoặc DTO của module pricing (BR-122). */
public record BookingLineResponse(String type, String dayType, Instant startAt, int hours,
        long dailyRate, BigDecimal uplift, long amount) {
    /** Chép đúng số đã lưu, không tính lại theo giá hoặc lịch lễ hiện tại. */
    public static BookingLineResponse fromDomain(BookingPriceLine line) {
        return new BookingLineResponse(line.type(), line.dayType(), line.startAt(), line.hours(),
                line.dailyRate(), line.uplift(), line.amount());
    }
}
