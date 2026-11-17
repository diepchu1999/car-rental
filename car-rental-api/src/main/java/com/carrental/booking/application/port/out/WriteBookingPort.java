package com.carrental.booking.application.port.out;

import com.carrental.booking.domain.Booking;

/** Ghi đơn, dòng giá và lịch sử trong transaction của use case (BR-102/122/131). */
public interface WriteBookingPort {
    /** BR-103: CAS PENDING_PAYMENT đến hạn và ghi HOLD_EXPIRED với tác nhân hệ thống (null). */
    boolean cancelExpiredPending(Booking cancelled);

    /**
     * Chỉ nhận đơn mới PENDING_PAYMENT. False chỉ khi trùng uq_booking_code; không ghi dòng con khi đó.
     * PendingBookingExistsException chỉ cho uq_booking_pending_customer; mọi lỗi phải rollback transaction ngoài.
     * Không tự mở transaction riêng: giữ chỗ và đơn phải cùng commit hoặc cùng rollback.
     */
    boolean insert(Booking booking);

    /**
     * BR-301: nhận bản CANCELLED từ domain, cập nhật đúng chủ chỉ khi CSDL còn PENDING_PAYMENT.
     * Ghi một lịch sử CUSTOMER_CANCELLED cùng câu SQL; false khi không còn khớp, không ghi lịch sử.
     * Tham gia transaction ngoài để use case nhả chỗ cùng commit/rollback (BR-304).
     */
    boolean cancelPendingByCustomer(Booking cancelled);
}
