package com.carrental.vehicle.application.view;

/** Kết quả use case đã phân giải thế chấp BR-110/209, không còn loại sở hữu. */
public record VehicleSearchSummary(long id, String code, long branchId, int seats,
        String transmission, String fuelType, String make, String model, boolean collateralFree) {
}
