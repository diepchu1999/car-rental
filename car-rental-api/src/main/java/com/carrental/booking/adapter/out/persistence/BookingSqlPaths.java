package com.carrental.booking.adapter.out.persistence;

/** Đường dẫn SQL riêng của booking, không truy cập bảng thuộc module khác. */
final class BookingSqlPaths {
    static final String FIND_FOR_EXPIRY = "sql/booking/find_booking_for_expiry.sql";
    static final String FIND_EXPIRED_PENDING = "sql/booking/find_expired_pending_bookings.sql";
    static final String CANCEL_EXPIRED_PENDING = "sql/booking/cancel_expired_pending_booking.sql";
    static final String INSERT = "sql/booking/insert_booking.sql";
    static final String INSERT_LINE = "sql/booking/insert_booking_line.sql";
    static final String INSERT_CREATED = "sql/booking/insert_booking_created_history.sql";
    static final String CANCEL_BY_CUSTOMER = "sql/booking/cancel_pending_booking_by_customer.sql";
    static final String FIND_OWNED = "sql/booking/find_owned_booking.sql";
    static final String FIND_LINES = "sql/booking/find_booking_lines.sql";
    static final String SEARCH_OWNED = "sql/booking/search_owned_bookings.sql";
    static final String CLAIM_KEY = "sql/booking/claim_booking_idempotency.sql";
    static final String LOCK_KEY = "sql/booking/lock_booking_idempotency.sql";
    static final String REPLACE_KEY_BOOKING = "sql/booking/replace_claimed_booking_code.sql";
    /** Tiện ích không có instance. */
    private BookingSqlPaths() { }
}
