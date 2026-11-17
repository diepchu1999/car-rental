package com.carrental.booking.application.port.in;

/** BR-103: điều phối lượt dọn, không giữ một transaction bao quanh cả đợt. */
public interface SweepExpiredBookingHoldsUseCase {
    /** Trả số dòng vừa xử lý thành công; lỗi từng dòng được ghi log và bỏ qua. */
    int sweep();
}
