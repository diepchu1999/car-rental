package com.carrental.pricing.application.view;

import com.carrental.pricing.domain.CalculatedPrice;
import com.carrental.pricing.domain.QuoteSnapshot;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/** Mô hình đọc báo giá đã lưu, tách khỏi aggregate đầu vào của cổng ghi và response HTTP. */
public record QuoteDetail(String code, String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
        QuoteSnapshot snapshot, CalculatedPrice price, Instant createdAt, Instant expiresAt) { }
