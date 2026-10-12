package com.carrental.pricing.application.port.in;

import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.pricing.application.view.QuoteDetail;

/** Đọc đúng bản báo giá đã lưu theo BR-122/ADR-0014. */
public interface GetQuoteUseCase {
    /** Báo giá đã hết hạn vẫn đọc được; thiếu mã trả QUOTE_NOT_FOUND. */
    QuoteDetail get(GetQuoteQuery query);
}
