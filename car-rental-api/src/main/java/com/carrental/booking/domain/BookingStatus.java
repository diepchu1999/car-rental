package com.carrental.booking.domain;

/** Trạng thái thuộc lát cắt tạo/huỷ đơn; các bước trả cọc và bàn giao chưa mở (status-flow §1). */
public enum BookingStatus {
    /** Đã giữ chỗ, đang chờ trả cọc (BR-102/103/131). */
    PENDING_PAYMENT,
    /** Đã huỷ do khách hoặc hết hạn; không thu hay hoàn tiền trong lát cắt này (BR-103/301). */
    CANCELLED
}
