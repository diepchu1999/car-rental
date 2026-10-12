package com.carrental.vehicle.application.view;

/** Giá ngày của một xe ACTIVE, không phải response HTTP hoặc snapshot báo giá. */
public record VehiclePriceDetail(String code, long dailyRate) { }
