package com.carrental.pricing.application.service;

import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import com.carrental.pricing.domain.Quote;
import java.util.Optional;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** GET chỉ đọc dữ liệu đã lưu, không có nguồn cấu hình hoặc Clock để tính lại giá/hạn. */
class QuoteQueryServiceTest {
    /** Báo giá có createdAt/hạn trong quá khứ vẫn trả đúng snapshot cũ, chỉ đọc đúng một lần. */
    @Test
    void returnsStoredExpiredQuoteWithoutRepricing() {
        var port = mock(ReadQuotePort.class);
        var base = quote("BG-2611-TEST01", Duration.ofNanos(1));
        var expected = detail(Quote.create("BG-0001-TEST01", base.vehicleCode(), base.startAt(), base.endAt(),
                base.rentalType(), base.driveMode(), base.pickupMethod(), base.snapshot(), base.price(),
                Instant.parse("2000-01-01T00:00:00Z")));
        when(port.findByCode(expected.code())).thenReturn(Optional.of(expected));
        assertSame(expected, new QuoteQueryService(port).get(GetQuoteQuery.from(expected.code())));
        verify(port).findByCode(expected.code()); verifyNoMoreInteractions(port);
    }

    /** Mã có nội dung nhưng không tồn tại trả QUOTE_NOT_FOUND thuộc nhóm 404. */
    @Test
    void missingQuoteReturnsNotFound() {
        var port = mock(ReadQuotePort.class);
        var failure = assertThrows(DomainException.class,
                () -> new QuoteQueryService(port).get(GetQuoteQuery.from("missing-quote")));
        assertEquals(ErrorCode.QUOTE_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }
}
