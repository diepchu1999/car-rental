package com.carrental.pricing.adapter.in.internal;

import com.carrental.pricing.api.QuoteDirectory;
import com.carrental.pricing.api.QuoteLineRef;
import com.carrental.pricing.api.QuoteRef;
import com.carrental.pricing.application.port.in.GetQuoteForBookingUseCase;
import com.carrental.pricing.application.query.GetQuoteForBookingQuery;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Biên module pricing: chuyển snapshot đã kiểm quyền/hạn sang hợp đồng công khai cho booking. */
@Component
class QuoteDirectoryAdapter implements QuoteDirectory {
    private final GetQuoteForBookingUseCase useCase;

    /** Chỉ phụ thuộc port/in, không truy vấn SQL hoặc tự quyết quyền tại adapter. */
    QuoteDirectoryAdapter(GetQuoteForBookingUseCase useCase) { this.useCase = useCase; }

    /** Giữ nguyên giá trị đã lưu; không tính lại các dòng hoặc làm tròn lần nữa. */
    @Override
    public QuoteRef getForBooking(String code, String customerAccountCode, Instant at) {
        var quote = useCase.getForBooking(new GetQuoteForBookingQuery(code, customerAccountCode, at));
        var snapshot = quote.snapshot();
        var price = quote.price();
        return new QuoteRef(quote.code(), quote.customerAccountCode(), quote.vehicleCode(), quote.startAt(),
                quote.endAt(), quote.rentalType(), quote.driveMode(), quote.pickupMethod(), snapshot.dailyRate(),
                price.lines().stream().map(line -> new QuoteLineRef(line.type().name(), line.dayType().name(),
                        line.startAt(), line.hours(), line.dailyRate(), line.uplift(), line.amount())).toList(),
                snapshot.insurance().calculation().name(), snapshot.insurance().value(), snapshot.insuranceBillingDays(),
                price.rentalAmount(), price.insuranceFee(), price.totalAmount(), quote.createdAt(), quote.expiresAt());
    }
}
