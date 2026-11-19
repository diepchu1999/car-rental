package com.carrental.booking.application.query;

import com.carrental.shared.error.DomainException;

/** Đầu vào danh sách đơn riêng của khách; không nhận mã chủ từ client (security-guideline §2). */
public record SearchBookingsQuery(Integer limit, String cursor) {
    /** api-guideline §8: mặc định 20, giới hạn 100; cursor rỗng hoặc quá dài không phải trang đầu. */
    public SearchBookingsQuery {
        limit = limit == null ? 20 : limit;
        if (limit < 1 || limit > 100) throw DomainException.invalidInput("limit must be between 1 and 100.");
        if (cursor != null && (cursor.isBlank() || cursor.length() > 256)) {
            throw DomainException.invalidInput("cursor is invalid.");
        }
    }

    /** Nhận tham số thô, không phụ thuộc DTO của REST. */
    public static SearchBookingsQuery from(Integer limit, String cursor) {
        return new SearchBookingsQuery(limit, cursor);
    }
}
