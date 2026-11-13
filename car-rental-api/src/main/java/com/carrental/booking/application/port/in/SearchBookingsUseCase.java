package com.carrental.booking.application.port.in;

import com.carrental.booking.application.query.SearchBookingsQuery;
import com.carrental.booking.application.view.BookingsPage;

/** Danh sách đơn thuộc khách hiện tại theo security-guideline §2 và api-guideline §8. */
public interface SearchBookingsUseCase {
    /** Trang mới nhất trước; không định giá lại hoặc tự đổi trạng thái đơn khi đọc. */
    BookingsPage search(SearchBookingsQuery query);
}
