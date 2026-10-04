package com.carrental.availability.api;

import java.time.Instant;

/**
 * Truyền khoảng thời gian giữa module gọi và availability.
 *
 * <p>Khoảng được hiểu theo dạng cận dưới đóng, cận trên mở:
 * [startInclusive, endExclusive), theo ADR-0005.
 *
 * <p>endExclusive bằng null biểu diễn khoảng không chặn trên
 * dành cho COMPLIANCE_HOLD theo BR-015.
 * Luồng giữ chỗ thuê xe phải sử dụng khoảng hữu hạn.
 *
 * <p>Với thao tác giữ chỗ hoặc tìm xe bận, đây là khoảng thuê
 * chưa cộng đệm. Availability nhận độ dài đệm riêng và tự cộng
 * theo BR-109, BR-116; module gọi không cộng trước.
 *
 * <p>Đây chỉ là dữ liệu của hợp đồng giao tiếp nội bộ.
 * Application và domain kiểm tra đầu mút cùng điều kiện sử dụng
 * khi thực hiện thao tác. Record không truy cập database,
 * không kiểm xe trống và không tự đọc đồng hồ.
 *
 * <p>Không phải java.time.Period: kiểu của JDK biểu diễn số năm,
 * tháng, ngày; kiểu này biểu diễn hai mốc thời gian cụ thể.
 *
 * @param startInclusive thời điểm bắt đầu, bắt buộc khi thực hiện thao tác
 * @param endExclusive thời điểm kết thúc không thuộc khoảng;
 *                     null nếu không chặn trên
 */
public record Period(
        Instant startInclusive,
        Instant endExclusive
) {
}