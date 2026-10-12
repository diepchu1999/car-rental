package com.carrental.pricing.application.port.out;

import com.carrental.pricing.application.view.QuoteDetail;
import java.util.Optional;

/** Nguồn đọc snapshot báo giá, không tham chiếu giá xe hoặc cấu hình hiện tại. */
public interface ReadQuotePort {
    /** Đọc header và các dòng đúng thứ tự; không loại báo giá hết hạn. */
    Optional<QuoteDetail> findByCode(String code);
}
