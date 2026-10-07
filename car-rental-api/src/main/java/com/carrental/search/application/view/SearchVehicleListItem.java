package com.carrental.search.application.view;

/**
 * Xe phù hợp tại thời điểm đọc theo BR-110/112/125; không có giá hoặc loại sở hữu.
 * vehicleId chỉ phục vụ cursor nội bộ; REST sẽ ánh xạ sang response không có ID này.
 * Khoảng cách theo mét nguyên độ chính xác từ PostGIS, không làm tròn trước khi phân trang.
 */
public record SearchVehicleListItem(long vehicleId, String code, String make, String model,
        int seats, String transmission, String fuelType, boolean collateralFree,
        SearchBranchSummary branch, double distanceMeters) {
}
