package com.carrental.booking.domain;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hợp đồng lưu key theo api-guideline §5, không cho bên gọi chọn thời hạn khác. */
class BookingIdempotencyTest {
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00.123456Z");

    /** TTL là đúng 24 giờ, key không bị trim và không phụ thuộc ngày lịch/timezone. */
    @Test
    void retainsExactKeyForTwentyFourHours() {
        var key = BookingIdempotency.create("TK-TEST01", " key ", "BG-2611-TEST01", "DT-2611-TEST01", NOW);
        assertEquals(" key ", key.key());
        assertEquals(NOW.plus(Duration.ofHours(24)), key.expiresAt());
        assertEquals(NOW, key.createdAt());
    }

    /** Không thể khôi phục một TTL tuỳ chọn hoặc timestamp mà PostgreSQL sẽ làm tròn khác đi. */
    @Test
    void rejectsDifferentRetentionAndSubmicroTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> new BookingIdempotency("TK-TEST01", "key",
                "BG-2611-TEST01", "DT-2611-TEST01", NOW, NOW.plusSeconds(86399)));
        assertThrows(IllegalArgumentException.class, () -> BookingIdempotency.create("TK-TEST01", "key",
                "BG-2611-TEST01", "DT-2611-TEST01", NOW.plusNanos(1)));
    }

    /** Key trắng hoặc mã ngoài định dạng không trở thành dữ liệu idempotency hợp lệ. */
    @Test
    void rejectsMissingKeyAndInvalidReferences() {
        assertThrows(IllegalArgumentException.class, () -> BookingIdempotency.create("TK-TEST01", " ",
                "BG-2611-TEST01", "DT-2611-TEST01", NOW));
        assertThrows(IllegalArgumentException.class, () -> BookingIdempotency.create("1", "key",
                "BG-2611-TEST01", "DT-2611-TEST01", NOW));
        assertThrows(IllegalArgumentException.class, () -> BookingIdempotency.create("TK-TEST01", "key",
                "1", "DT-2611-TEST01", NOW));
        assertThrows(IllegalArgumentException.class, () -> BookingIdempotency.create("TK-TEST01", "key",
                "BG-2611-TEST01", "DT-2613-TEST01", NOW));
    }
}
