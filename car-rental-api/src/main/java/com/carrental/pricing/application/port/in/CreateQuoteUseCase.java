package com.carrental.pricing.application.port.in;

import com.carrental.pricing.application.command.CreateQuoteCommand;
import com.carrental.pricing.application.view.QuoteDetail;

/** Tạo tài nguyên báo giá bất biến theo BR-122, không phải thao tác thu tiền hay giữ chỗ. */
public interface CreateQuoteUseCase {
    /** Kiểm điều kiện, tính, lưu và đọc lại trong cùng transaction; không dùng idempotency key. */
    QuoteDetail create(CreateQuoteCommand command);
}
