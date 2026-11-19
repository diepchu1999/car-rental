package com.carrental.pricing.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.pricing.application.port.out.ReadQuotePort;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.pricing.application.query.GetQuoteForBookingQuery;
import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-122: chủ và hạn được kiểm tại use case, không dựa vào controller hay mã trên URL. */
class QuoteQueryServiceTest {
    private final ReadQuotePort reads = mock(ReadQuotePort.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final QuoteQueryService service = new QuoteQueryService(reads, callers);
    private final QuoteDetail saved = detail(quote("BG-2611-TEST01", Duration.ofMinutes(15)));

    /** Danh tính tường minh; không có Clock hoặc nguồn cấu hình để tính lại giá/hạn. */
    @BeforeEach
    void setUp() {
        when(callers.current()).thenReturn(new CallerRef(OWNER, Set.of("CUSTOMER"), null));
    }

    /** GET của chủ trả nguyên snapshot; không quan tâm đồng hồ hiện tại hoặc hạn dùng để đặt xe. */
    @Test
    void returnsStoredExpiredQuoteWithoutRepricing() {
        when(reads.findByCode(saved.code())).thenReturn(Optional.of(saved));
        assertSame(saved, service.get(GetQuoteQuery.from(saved.code())));
        verify(reads).findByCode(saved.code()); verifyNoMoreInteractions(reads);
    }

    /** Thiếu mã trả đúng lỗi 404 ở cả GET và cổng tạo đơn. */
    @Test
    void missingQuoteReturnsNotFound() {
        assertNotFound(() -> service.get(GetQuoteQuery.from("missing-quote")));
        assertNotFound(() -> service.getForBooking(new GetQuoteForBookingQuery("missing-quote", OWNER, CREATED_AT)));
    }

    /** Báo giá khác chủ hoặc lịch sử thiếu chủ đều bị giấu, kể cả khi báo giá đã hết hạn. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"TK-OTHER1"})
    void refusesWrongOrLegacyOwnerBeforeExpiry(String owner) {
        var other = new QuoteDetail(saved.code(), owner, saved.vehicleCode(), saved.startAt(), saved.endAt(),
                saved.rentalType(), saved.driveMode(), saved.pickupMethod(), saved.snapshot(), saved.price(),
                saved.createdAt(), saved.expiresAt());
        when(reads.findByCode(saved.code())).thenReturn(Optional.of(other));
        assertNotFound(() -> service.get(GetQuoteQuery.from(saved.code())));
        assertNotFound(() -> service.getForBooking(new GetQuoteForBookingQuery(saved.code(), OWNER, saved.expiresAt())));
    }

    /** Chỉ còn hạn trước expiresAt; chênh một nano giây vẫn được bảo toàn qua cổng. */
    @Test
    void allowsOwnerOneNanosecondBeforeExpiry() {
        when(reads.findByCode(saved.code())).thenReturn(Optional.of(saved));
        assertSame(saved, service.getForBooking(new GetQuoteForBookingQuery(
                saved.code(), OWNER, saved.expiresAt().minusNanos(1))));
        verifyNoInteractions(callers); // Cổng nội bộ nhận danh tính đã xác thực từ booking, không cần HTTP.
    }

    /** Tại đúng hạn và sau hạn đều từ chối bằng QUOTE_EXPIRED/422, không tự tính lại giá. */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 900_000_000_000L})
    void rejectsExpiredOwnedQuote(long nanosAfterExpiry) {
        when(reads.findByCode(saved.code())).thenReturn(Optional.of(saved));
        var failure = assertThrows(DomainException.class, () -> service.getForBooking(new GetQuoteForBookingQuery(
                saved.code(), OWNER, saved.expiresAt().plusNanos(nanosAfterExpiry))));
        assertEquals(ErrorCode.QUOTE_EXPIRED, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Quyền nhân viên không tự trở thành quyền khách khi gọi thẳng use case. */
    @Test
    void deniesNonCustomerBeforeReadingQuote() {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN"), null));
        var failure = assertThrows(DomainException.class, () -> service.get(GetQuoteQuery.from(saved.code())));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        verifyNoInteractions(reads);
    }

    /** Ngoài request không giả danh chủ và không truy vấn dữ liệu khách. */
    @Test
    void preservesMissingCallerFailure() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.get(GetQuoteQuery.from(saved.code()))));
        verifyNoInteractions(reads);
    }

    /** So sánh cả nhóm lỗi, tránh nhầm 403 với 404 khi giấu tài nguyên khác chủ. */
    private static void assertNotFound(Runnable operation) {
        var failure = assertThrows(DomainException.class, operation::run);
        assertEquals(ErrorCode.QUOTE_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }
}
