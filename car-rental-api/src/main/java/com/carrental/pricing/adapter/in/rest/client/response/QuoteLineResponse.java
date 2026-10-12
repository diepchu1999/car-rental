package com.carrental.pricing.adapter.in.rest.client.response;

import com.carrental.pricing.domain.QuoteLine;
import com.carrental.pricing.domain.QuoteLineType;
import com.carrental.pricing.domain.DayType;
import java.math.BigDecimal;
import java.time.Instant;

/** Dòng tiền thuê công khai, không có khóa chính quote_line; tiền bảo hiểm là trường riêng BR-231. */
public record QuoteLineResponse(QuoteLineType type, DayType dayType, Instant startAt, int hours,
        long dailyRate, BigDecimal uplift, long amount) {
    /** Ánh xạ đúng số tiền đã chốt, không gọi thuật toán giá trong REST adapter. */
    public static QuoteLineResponse fromDomain(QuoteLine line) {
        return new QuoteLineResponse(line.type(), line.dayType(), line.startAt(), line.hours(),
                line.dailyRate(), line.uplift(), line.amount());
    }
}
