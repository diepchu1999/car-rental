package com.carrental.pricing.application.service;

import com.carrental.pricing.application.port.in.GetQuoteUseCase;
import com.carrental.pricing.application.port.in.GetQuoteForBookingUseCase;
import com.carrental.pricing.application.query.GetQuoteForBookingQuery;
import com.carrental.identity.api.CallerDirectory;
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
class QuoteQueryService implements GetQuoteUseCase, GetQuoteForBookingUseCase {
    private final ReadQuotePort reads;
    private final CallerDirectory callers;

    /** Nhận đúng nguồn snapshot đã lưu. */
    QuoteQueryService(ReadQuotePort reads, CallerDirectory callers) { this.reads = reads; this.callers = callers; }

    /** BR-122: chỉ chủ đọc được, kể cả quá hạn; không cho ADMIN thuần bỏ qua quyền khách. */
    @Override
    @Transactional(readOnly = true)
    public QuoteDetail get(GetQuoteQuery query) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Validations.required(query, "query");
        return owned(query.code(), caller.accountCode());
    }

    /** Dùng mốc do booking chụp một lần; không đọc Clock hay cấu hình để tính lại hạn. */
    @Override
    @Transactional(readOnly = true)
    public QuoteDetail getForBooking(GetQuoteForBookingQuery query) {
        Validations.required(query, "query");
        var quote = owned(query.code(), query.customerAccountCode());
        if (!query.at().isBefore(quote.expiresAt())) {
            throw DomainException.ruleViolation(ErrorCode.QUOTE_EXPIRED);
        }
        return quote;
    }

    /** Mã sai chủ và lịch sử chưa có chủ đều giống mã không tồn tại, không lộ hạn hoặc snapshot. */
    private QuoteDetail owned(String code, String owner) {
        return reads.findByCode(code).filter(quote -> owner.equals(quote.customerAccountCode()))
                .orElseThrow(() -> DomainException.notFound(ErrorCode.QUOTE_NOT_FOUND));
    }
}
