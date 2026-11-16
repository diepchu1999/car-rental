package com.carrental.availability.api;

/** Kết quả nhả một hold theo BR-103, để bên điều phối quyết định commit/rollback cùng đơn. */
public enum HoldExpiryResult {
    /** Lượt hiện tại vừa đổi HELD đến hạn thành RELEASED. */
    RELEASED,
    /** Hold thuê đã RELEASED và hạn đã tới; không sửa lại mốc hoặc khoảng lịch. */
    ALREADY_RELEASED,
    /** Chưa đến hạn hoặc không còn là hold có thể hết hạn; bên gọi không được coi là đã nhả. */
    NOT_ELIGIBLE
}
