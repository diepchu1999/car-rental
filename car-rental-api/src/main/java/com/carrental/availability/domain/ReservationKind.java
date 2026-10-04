package com.carrental.availability.domain;

/**
 * Phân loại nguyên nhân khiến một xe bị khóa lịch.
 *
 * <p>Mọi nguyên nhân dùng chung bảng reservation và cùng chịu
 * ràng buộc chống chồng lịch theo BR-104, ADR-0005.
 *
 * <p>Loại khóa không biểu diễn trạng thái xử lý.
 * RENTAL được tạo ở trạng thái HELD; các loại khóa vận hành
 * được tạo trực tiếp ở trạng thái BLOCKED.
 */
public enum ReservationKind {

    /**
     * Khóa lịch phục vụ đơn thuê xe, bắt đầu bằng giữ chỗ
     * chờ thanh toán theo BR-102 và BR-103.
     */
    RENTAL,

    /**
     * Khóa lịch trong thời gian bảo dưỡng xe theo BR-011.
     */
    MAINTENANCE,

    /**
     * Khóa lịch trong thời gian đưa xe đi đăng kiểm theo BR-012.
     *
     * <p>Khác với COMPLIANCE_HOLD: đây là khoảng thực hiện
     * công việc đăng kiểm, không phải khóa do giấy tờ hết hạn.
     */
    INSPECTION,

    /**
     * Khóa lịch trong thời gian điều chuyển xe giữa các chi nhánh
     * theo BR-007.
     */
    TRANSFER,

    /**
     * Khóa lịch do chủ xe chủ động ngừng cung cấp xe
     * trong một khoảng thời gian theo BR-104.
     *
     * <p>Khai báo loại khóa trong mô hình không đồng nghĩa
     * với việc triển khai luồng dành cho đối tác ở task này.
     */
    OWNER_BLOCK,

    /**
     * Khóa lịch do giấy tờ bắt buộc của xe hết hạn theo BR-015.
     *
     * <p>Khóa bắt buộc không chặn trên và luôn BLOCKED, không hoàn tất hoặc giải phóng.
     * Việc theo dõi giấy tờ và tự tạo khóa
     * thuộc module fleet, không thuộc domain availability.
     */
    COMPLIANCE_HOLD
}
