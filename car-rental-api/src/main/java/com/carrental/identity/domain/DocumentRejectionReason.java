package com.carrental.identity.domain;

/** BR-130: sáu lý do từ chối cố định; ghi chú tự do bổ sung, không thay thế mã lý do. */
public enum DocumentRejectionReason {
    /** Ảnh mờ, loá hoặc bị che nên không đọc được. */
    ILLEGIBLE,
    /** Thiếu một mặt giấy tờ. */
    MISSING_SIDE,
    /** Thông tin khách nhập không khớp ảnh. */
    DATA_MISMATCH,
    /** Giấy tờ đã hết hạn. */
    EXPIRED,
    /** CCCD và GPLX không thuộc cùng một người. */
    HOLDER_MISMATCH,
    /** Nghi giả mạo hoặc không phải giấy tờ thật. */
    SUSPECTED_FORGERY
}
