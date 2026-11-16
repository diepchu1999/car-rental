package com.carrental.availability.api;

import java.time.Instant;

/** BR-103: ứng viên đến hạn tại lúc đọc, không bảo đảm vẫn HELD khi xử lý; không chứa ID CSDL. */
public record ExpiredHoldRef(String reservationCode, String bookingCode, Instant holdExpiresAt) { }
