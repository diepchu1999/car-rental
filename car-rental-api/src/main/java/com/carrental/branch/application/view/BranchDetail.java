package com.carrental.branch.application.view;

/**
 * Chứa dữ liệu chi tiết của chi nhánh đã được lưu.
 *
 * <p>Đây là mô hình đọc của application, không phải aggregate
 * và không phải response trả trực tiếp qua HTTP.
 *
 * <p>Tọa độ biểu diễn vị trí chi nhánh theo BR-003.
 * Khóa chính và mã nghiệp vụ tuân theo database-guideline mục 2.
 *
 * <p>Khóa chính chỉ phục vụ xử lý nội bộ. REST adapter sẽ chuyển
 * dữ liệu này thành response riêng, không đưa khóa chính ra ngoài.
 * URL sử dụng mã nghiệp vụ của chi nhánh.
 *
 * @param id khóa chính nội bộ do cơ sở dữ liệu sinh
 * @param code mã nghiệp vụ của chi nhánh
 * @param latitude vĩ độ của chi nhánh, tính bằng độ
 * @param longitude kinh độ của chi nhánh, tính bằng độ
 * @param name tên chi nhánh theo BR-808
 * @param address địa chỉ chi nhánh theo BR-808
 */
public record BranchDetail(
        long id,
        String code,
        double latitude,
        double longitude,
        String name,
        String address
) {
}
