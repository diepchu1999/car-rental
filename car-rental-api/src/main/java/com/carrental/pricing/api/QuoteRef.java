package com.carrental.pricing.api;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Snapshot đủ để booking sao chép giá/chi tiết bảo hiểm theo BR-122/231, ADR-0014 lát cắt 1.
 * Không lộ domain pricing, ID số, lịch lễ hoặc bộ giá trị quyết toán chưa có ở lát cắt này.
 */
public record QuoteRef(String code, String customerAccountCode, String vehicleCode,
        Instant startAt, Instant endAt, RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
        long dailyRate, List<QuoteLineRef> lines, String insuranceCalculation, BigDecimal insuranceValue,
        int insuranceBillingDays, long rentalAmount, long insuranceFee, long totalAmount,
        Instant createdAt, Instant expiresAt) {
    /** Sao chép danh sách để module bên gọi không thay đổi snapshot đã nhận. */
    public QuoteRef { lines = List.copyOf(lines); }
}
