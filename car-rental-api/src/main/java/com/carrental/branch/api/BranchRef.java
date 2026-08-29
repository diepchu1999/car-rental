package com.carrental.branch.api;

/**
 * Cung cấp thông tin định danh tối thiểu của chi nhánh cho module khác.
 *
 * <p>Module vehicle dùng thông tin này để liên kết xe công ty
 * với chi nhánh đã tồn tại theo BR-003.
 *
 * <p>Đây là hợp đồng giao tiếp nội bộ giữa các module,
 * không phải aggregate, application view hoặc response HTTP.
 *
 * <p>Không đưa tọa độ hoặc thông tin quản lý vào hợp đồng này
 * vì luồng liên kết xe với chi nhánh hiện tại chưa cần chúng.
 *
 * @param id khóa chính nội bộ của chi nhánh đã được lưu
 * @param code mã nghiệp vụ của chi nhánh
 */
public record BranchRef(
        long id,
        String code
) {
}