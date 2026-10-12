package com.carrental.pricing.api;

import java.util.Objects;

/** Giá ngày đã đọc theo lô bởi bên gọi; không mang ID, sở hữu hoặc dependency vehicle (BR-126). */
public record VehicleDailyRate(String vehicleCode, long dailyRate) {
    /** Chặn hợp đồng nội bộ sai thay vì trả tiền thuê giả cho xe ACTIVE. */
    public VehicleDailyRate {
        Objects.requireNonNull(vehicleCode, "vehicleCode");
        if (!vehicleCode.matches("XE-[A-Z0-9]{6}") || dailyRate <= 0 || dailyRate % 1_000 != 0) {
            throw new IllegalArgumentException("Invalid vehicle daily rate.");
        }
    }
}
