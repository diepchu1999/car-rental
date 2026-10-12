package com.carrental.vehicle.api;

/**
 * Giá tối thiểu cần cho pricing, không chứa loại sở hữu hoặc khóa chính số.
 * @param code mã xe bất biến
 * @param dailyRate giá ngày BR-234, nguyên VND
 */
public record VehiclePricingRef(String code, long dailyRate) { }
