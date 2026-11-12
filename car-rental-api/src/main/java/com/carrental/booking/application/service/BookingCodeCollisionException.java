package com.carrental.booking.application.service;

import java.io.Serial;

/** Tín hiệu kỹ thuật để rollback đúng savepoint khi uq_booking_code từ chối mã ngẫu nhiên. */
final class BookingCodeCollisionException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Không đưa mã bị trùng hoặc thông tin lưu trữ vào thông báo. */
    BookingCodeCollisionException() { super("The generated booking code already exists."); }
}
