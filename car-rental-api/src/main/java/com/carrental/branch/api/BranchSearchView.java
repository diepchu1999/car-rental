package com.carrental.branch.api;

/**
 * Chi nhánh ứng viên trong bán kính theo BR-003, BR-808 và ADR-0007.
 * @param id khóa nội bộ để ghép xe, không dùng trên URL
 * @param code mã chi nhánh
 * @param name tên hiển thị
 * @param address địa chỉ hiển thị
 * @param distanceMeters khoảng cách PostGIS theo mét, chưa làm tròn để phân trang ổn định
 */
public record BranchSearchView(long id, String code, String name, String address, double distanceMeters) {
}
