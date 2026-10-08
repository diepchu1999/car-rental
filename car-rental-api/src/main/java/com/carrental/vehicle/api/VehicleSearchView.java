package com.carrental.vehicle.api;

/**
 * Dữ liệu tối thiểu cho tìm kiếm theo BR-018/110/112; không chứa loại sở hữu hay biển số.
 * ID xe dùng hỏi availability, ID chi nhánh dùng ghép kết quả; không phải định danh URL công khai.
 *
 * @param id định danh nội bộ xe
 * @param code mã xe công khai
 * @param branchId định danh nội bộ chi nhánh
 * @param seats số chỗ
 * @param transmission tên hộp số
 * @param fuelType tên loại nhiên liệu
 * @param make hãng xe giữ nguyên cách viết đã lưu
 * @param model dòng xe giữ nguyên cách viết đã lưu
 * @param collateralFree có miễn thế chấp cho gói thuê đang xét không
 */
public record VehicleSearchView(long id, String code, long branchId, int seats,
        String transmission, String fuelType, String make, String model, boolean collateralFree) {
}
