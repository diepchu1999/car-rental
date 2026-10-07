package com.carrental.branch.application.view;

/**
 * Read model nội bộ của truy vấn địa lý theo BR-003 và ADR-0007.
 * @param id khóa nội bộ chi nhánh
 * @param code mã nghiệp vụ
 * @param name tên hiển thị theo BR-808
 * @param address địa chỉ hiển thị theo BR-808
 * @param distanceMeters khoảng cách spheroid theo mét, không làm tròn ở Java
 */
public record BranchDistanceSummary(long id, String code, String name, String address, double distanceMeters) {
}
