package com.carrental.availability.api;

/**
 * Công bố các nguyên nhân khóa vận hành mà module khác được yêu cầu.
 *
 * <p>Mọi nguyên nhân đều tạo khóa BLOCKED và dùng chung cơ chế
 * chống chồng lịch theo BR-104, ADR-0005.
 *
 * <p>Không có RENTAL: đơn thuê phải đi qua thao tác hold,
 * tạo HELD với thời hạn do hệ thống áp dụng theo BR-102, BR-103.
 *
 * <p>Enum thuộc hợp đồng api, không phụ thuộc ReservationKind
 * trong domain. Adapter biên chuyển sang kiểu domain khi tạo command;
 * application/domain kiểm tra điều kiện sử dụng loại khóa đó.
 */
public enum BlockKind {

    /**
     * Xe bận trong thời gian bảo dưỡng theo BR-011.
     */
    MAINTENANCE,

    /**
     * Xe bận trong thời gian đi đăng kiểm theo BR-012.
     */
    INSPECTION,

    /**
     * Xe bận trong thời gian điều chuyển chi nhánh theo BR-007.
     */
    TRANSFER,

    /**
     * Chủ xe chủ động khóa lịch theo BR-104.
     *
     * <p>Khai báo giá trị không triển khai thêm luồng đối tác
     * hoặc giao diện dành cho chủ xe trong task hiện tại.
     */
    OWNER_BLOCK,

    /**
     * Xe bị khóa do giấy tờ bắt buộc hết hạn theo BR-015.
     *
     * <p>Khóa bắt buộc không chặn trên và luôn BLOCKED, không hoàn tất hoặc giải phóng.
     * Việc theo dõi giấy tờ để phát sinh yêu cầu thuộc module fleet.
     */
    COMPLIANCE_HOLD
}
