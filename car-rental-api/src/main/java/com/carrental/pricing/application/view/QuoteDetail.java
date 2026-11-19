package com.carrental.pricing.application.view;

import com.carrental.pricing.domain.CalculatedPrice;
import com.carrental.pricing.domain.QuoteSnapshot;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/** Mô hình đọc snapshot; customerAccountCode chỉ null với lịch sử trước V015, không được cấp cho khách. */
public record QuoteDetail(String code, String customerAccountCode, String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
        QuoteSnapshot snapshot, CalculatedPrice price, Instant createdAt, Instant expiresAt) { }
