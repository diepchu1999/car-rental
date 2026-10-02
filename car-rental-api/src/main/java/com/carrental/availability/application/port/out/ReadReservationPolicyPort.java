package com.carrental.availability.application.port.out;

import java.time.Duration;
import java.time.Instant;

/**
 * Cổng đọc chính sách khóa lịch theo mốc thời gian, tuân theo ADR-0013.
 *
 * <p>BR-103 và BR-225 yêu cầu hệ thống quyết định thời hạn giữ chỗ;
 * bên gọi AvailabilityDirectory không được chọn thời hạn này.
 * Nhịp chạy job dọn là tham số vận hành, không thuộc cổng chính sách.
 */
public interface ReadReservationPolicyPort {

    /**
     * Đọc thời hạn giữ chỗ áp dụng tại thời điểm tạo khóa lịch.
     *
     * <p>Application dùng cùng một mốc Clock để đọc chính sách và tạo
     * khóa lịch. Hạn được tính một lần rồi lưu; không đọc lại chính sách
     * để tính lại hạn khi xác nhận, giải phóng hoặc dọn bản ghi đã tồn tại.
     *
     * @param at mốc thời gian cần xác định chính sách, không được null
     * @return thời hạn giữ chỗ lớn hơn không
     */
    Duration holdDuration(Instant at);
}
