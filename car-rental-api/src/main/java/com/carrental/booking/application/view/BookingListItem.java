package com.carrental.booking.application.view;

import com.carrental.booking.domain.BookingStatus;
import java.time.Instant;

/** Tóm tắt đơn từ dữ liệu đã lưu (BR-122); không tải dòng giá hoặc cấu hình để hiển thị danh sách. */
public record BookingListItem(String code, String vehicleCode, String branchCode,
        Instant startAt, Instant endAt, BookingStatus status, long totalAmount, long deposit,
        long balance, Instant holdExpiresAt, Instant createdAt) { }
