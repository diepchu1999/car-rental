package com.carrental.pricing.adapter.in.rest.client.response;

import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;
import java.util.List;

/** Hợp đồng báo giá theo BR-122/231, không trả ID số hoặc toàn bộ cấu hình/lịch lễ nội bộ. */
public record QuoteResponse(String code, String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod, List<QuoteLineResponse> lines,
        long rentalAmount, long insuranceFee, long totalAmount, Instant expiresAt, Instant createdAt) {
    /** Chọn dữ liệu đã lưu cho client; không tính lại từ giá xe hiện tại. */
    public static QuoteResponse fromDomain(QuoteDetail detail) {
        return new QuoteResponse(detail.code(), detail.vehicleCode(), detail.startAt(), detail.endAt(),
                detail.rentalType(), detail.driveMode(), detail.pickupMethod(),
                detail.price().lines().stream().map(QuoteLineResponse::fromDomain).toList(),
                detail.price().rentalAmount(), detail.price().insuranceFee(), detail.price().totalAmount(),
                detail.expiresAt(), detail.createdAt());
    }
}
