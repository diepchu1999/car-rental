package com.carrental.pricing.application.service;

import com.carrental.pricing.application.port.in.GetQuoteUseCase;
import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** GET không tính lại giá hoặc hạn, không đọc Clock, giá xe hay tham số hiện tại (ADR-0014). */
@Service
class QuoteQueryService implements GetQuoteUseCase {
    private final ReadQuotePort reads;

    /** Nhận đúng nguồn snapshot đã lưu. */
    QuoteQueryService(ReadQuotePort reads) { this.reads = reads; }

    /** Báo giá quá hạn vẫn đọc được; kiểm QUOTE_EXPIRED khi đặt xe thuộc task booking. */
    @Override
    @Transactional(readOnly = true)
    public QuoteDetail get(GetQuoteQuery query) {
        Validations.required(query, "query");
        return reads.findByCode(query.code()).orElseThrow(() -> DomainException.notFound(ErrorCode.QUOTE_NOT_FOUND));
    }
}
