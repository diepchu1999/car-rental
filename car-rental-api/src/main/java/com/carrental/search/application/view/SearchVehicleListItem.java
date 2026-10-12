package com.carrental.search.application.view;

/**
 * Xe phù hợp BR-110/112/125/126; tiền thuê chưa bảo hiểm, không có loại sở hữu.
 * vehicleId chỉ dùng nội bộ; cursor và REST dùng mã xe, không có ID này.
 * Khoảng cách theo mét nguyên độ chính xác từ PostGIS, không làm tròn trước khi phân trang.
 */
public record SearchVehicleListItem(long vehicleId, String code, String make, String model,
        int seats, String transmission, String fuelType, boolean collateralFree,
        SearchBranchSummary branch, double distanceMeters, long rentalAmount) {
}
