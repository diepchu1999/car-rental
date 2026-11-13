package com.carrental.booking.application.view;

import java.util.List;

/** Trang đơn riêng của khách; nextCursor chỉ có khi còn hàng sau phần đã trả. */
public record BookingsPage(List<BookingListItem> items, String nextCursor, boolean hasMore) {
    /** Chụp danh sách bất biến; không giữ tham chiếu subList có thể bị sửa từ bên ngoài. */
    public BookingsPage { items = List.copyOf(items); }
}
