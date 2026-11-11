package com.carrental.booking.application.port.out;

import com.carrental.booking.domain.Booking;

/** Ghi đơn, dòng giá và lịch sử trong transaction của use case (BR-102/122/131). */
public interface WriteBookingPort {
    /**
     * Chỉ nhận đơn mới PENDING_PAYMENT. False chỉ khi trùng uq_booking_code; không ghi dòng con khi đó.
     * PendingBookingExistsException chỉ cho uq_booking_pending_customer; mọi lỗi phải rollback transaction ngoài.
     * Không tự mở transaction riêng: giữ chỗ và đơn phải cùng commit hoặc cùng rollback.
     */
    boolean insert(Booking booking);
}
