package com.carrental.pricing.api;

import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/** Cổng giá công khai cho search theo BR-126; booking terms do bên điều phối kiểm trước. */
public interface RentalPricingDirectory {
    /** Kiểm lịch lễ ngay cả khi không có xe, chuẩn bị hệ số một lần cho cả lô, BR-218/235. */
    RentalPricePlan prepare(RentalType rentalType, Instant startAt, Instant endAt);
}
