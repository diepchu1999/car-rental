package com.carrental.pricing.application.query;

import com.carrental.shared.error.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** BR-122: hợp đồng nội bộ không tự chọn chủ hoặc mốc kiểm hạn khi bên gọi bỏ thiếu. */
class GetQuoteForBookingQueryTest {
    /** Giữ nguyên mã, chủ và độ chính xác nano của mốc đã chọn ở bên tạo đơn. */
    @Test
    void preservesInputsWithoutTrimmingOrRounding() {
        var at = Instant.parse("2026-11-01T03:00:00.000000001Z");
        var query = new GetQuoteForBookingQuery(" BG-2611-TEST01 ", "TK-OWNER1", at);
        assertEquals(" BG-2611-TEST01 ", query.code());
        assertEquals("TK-OWNER1", query.customerAccountCode()); assertEquals(at, query.at());
    }

    /** Thiếu mã/chủ/mốc hoặc chủ không đúng mẫu đều bị từ chối ngay ở biên use case. */
    @Test
    void rejectsMissingOrMalformedInputs() {
        var at = Instant.parse("2026-11-01T03:00:00Z");
        assertThrows(DomainException.class, () -> new GetQuoteForBookingQuery(null, "TK-OWNER1", at));
        assertThrows(DomainException.class, () -> new GetQuoteForBookingQuery(" ", "TK-OWNER1", at));
        assertThrows(DomainException.class, () -> new GetQuoteForBookingQuery("BG-2611-TEST01", null, at));
        assertThrows(DomainException.class, () -> new GetQuoteForBookingQuery("BG-2611-TEST01", " ", at));
        assertThrows(DomainException.class, () -> new GetQuoteForBookingQuery("BG-2611-TEST01", "TK-OWNER1", null));
        assertThrows(IllegalArgumentException.class, () -> new GetQuoteForBookingQuery("BG-2611-TEST01", "XE-OWNER1", at));
    }
}
