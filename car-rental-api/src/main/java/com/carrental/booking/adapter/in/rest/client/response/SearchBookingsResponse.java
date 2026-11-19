package com.carrental.booking.adapter.in.rest.client.response;

import com.carrental.booking.application.view.BookingsPage;
import java.util.List;

/** Trang lịch sử đơn theo api-guideline §8; nextCursor null khi lần đọc này không còn trang sau. */
public record SearchBookingsResponse(List<BookingListItemResponse> items, String nextCursor, boolean hasMore) {
    /** Không cho thay danh sách sau khi response được tạo. */
    public SearchBookingsResponse { items = List.copyOf(items); }

    /** Chuyển từng read model sang DTO, giữ nguyên metadata do application xác định. */
    public static SearchBookingsResponse fromDomain(BookingsPage page) {
        return new SearchBookingsResponse(page.items().stream().map(BookingListItemResponse::fromDomain).toList(),
                page.nextCursor(), page.hasMore());
    }
}
