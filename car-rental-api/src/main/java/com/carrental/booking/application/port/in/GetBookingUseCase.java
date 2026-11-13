package com.carrental.booking.application.port.in;

import com.carrental.booking.application.query.GetBookingQuery;
import com.carrental.booking.application.view.BookingDetail;

/** Cổng đọc chi tiết đơn của chính khách, theo security-guideline §2. */
public interface GetBookingUseCase {
    /** Trả trạng thái hiện tại và snapshot BR-122; mã sai chủ giống mã không tồn tại. */
    BookingDetail get(GetBookingQuery query);
}
