package com.carrental.pricing.api;

import java.util.List;
import java.util.Map;

/** Khoảng thuê đã kiểm lịch và phân loại ngày một lần, không lộ domain pricing (BR-218/126). */
@FunctionalInterface
public interface RentalPricePlan {
    /** Tính theo lô từ giá đã có; không truy vấn CSDL, không bảo hiểm, không lưu báo giá. */
    Map<String, Long> rentalAmounts(List<VehicleDailyRate> vehicles);
}
