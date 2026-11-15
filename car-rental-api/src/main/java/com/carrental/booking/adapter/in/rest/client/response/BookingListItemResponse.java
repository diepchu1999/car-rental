package com.carrental.booking.adapter.in.rest.client.response;

import com.carrental.booking.application.view.BookingListItem;
import com.carrental.booking.domain.BookingStatus;
import java.time.Instant;

/** Tóm tắt đơn của khách; chỉ mã nghiệp vụ và giá đã lưu, không lộ chủ hoặc mã khóa lịch nội bộ. */
public record BookingListItemResponse(String code, String vehicleCode, String branchCode,
        Instant startAt, Instant endAt, BookingStatus status, long totalAmount, long deposit,
        long balance, Instant holdExpiresAt, Instant createdAt) {
    /** BR-122/ADR-0014: ánh xạ snapshot, không định giá lại và không giả định trạng thái theo giờ hiện tại. */
    public static BookingListItemResponse fromDomain(BookingListItem item) {
        return new BookingListItemResponse(item.code(), item.vehicleCode(), item.branchCode(), item.startAt(),
                item.endAt(), item.status(), item.totalAmount(), item.deposit(), item.balance(),
                item.holdExpiresAt(), item.createdAt());
    }
}
