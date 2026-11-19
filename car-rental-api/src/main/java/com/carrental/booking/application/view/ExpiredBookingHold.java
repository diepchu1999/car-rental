package com.carrental.booking.application.view;

/** BR-103: tham chiếu đơn chờ đến hạn; không đọc bảng availability hoặc mang ID CSDL. */
public record ExpiredBookingHold(String bookingCode, String reservationCode) { }
