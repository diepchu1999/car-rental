package com.carrental.pricing.application.port.in;

import com.carrental.pricing.application.query.GetQuoteForBookingQuery;
import com.carrental.pricing.application.view.QuoteDetail;

/** Đọc snapshot đúng chủ và còn hạn để tạo đơn theo BR-122, không tính lại giá. */
public interface GetQuoteForBookingUseCase {
    /** Kiểm chủ trước hạn; hạn bằng mốc kiểm tra cũng là hết hạn. */
    QuoteDetail getForBooking(GetQuoteForBookingQuery query);
}
