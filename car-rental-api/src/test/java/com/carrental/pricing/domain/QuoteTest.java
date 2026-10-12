package com.carrental.pricing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Arrays;
import static com.carrental.pricing.domain.QuoteTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Khóa snapshot và hạn của báo giá theo BR-122, ADR-0014, không phụ thuộc Clock hiện tại. */
class QuoteTest {
    /** Hạn lấy giá trị tại tạo; snapshot không sao chép lịch lễ và các dòng không sửa được. */
    @Test
    void freezesExpiryAndRequiredParametersWithoutCopyingCalendar() {
        var quote = quote("BG-2611-TEST01", Duration.ofMinutes(30));
        assertEquals(CREATED_AT.plusSeconds(1_800), quote.expiresAt());
        assertEquals(2, quote.snapshot().insuranceBillingDays());
        assertEquals(2_200_000, quote.price().rentalAmount());
        assertEquals(200_000, quote.price().insuranceFee());
        assertFalse(Arrays.stream(QuoteSnapshot.class.getRecordComponents())
                .anyMatch(component -> component.getType() == HolidayCalendar.class));
        assertThrows(UnsupportedOperationException.class, () -> quote.price().lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> quote.snapshot().weekendDays().clear());
    }

    /** Aggregate không chấp nhận mã ngoài hợp đồng, kể cả tháng 00 hoặc 13. */
    @ParameterizedTest
    @ValueSource(strings = {"", "BG-2613-TEST01", "BG-2600-TEST01", "BG-2611-test01", "BG-2611-TEST0"})
    void rejectsMalformedCode(String code) {
        assertThrows(IllegalArgumentException.class, () -> quote(code, Duration.ofMinutes(15)));
    }

    /** Constructor không cho gán hạn khác snapshot để làm thay đổi dữ liệu đã chốt. */
    @Test
    void rejectsExpiryNotMatchingFrozenValidity() {
        var quote = quote("BG-2611-TEST01", Duration.ofMinutes(15));
        assertThrows(IllegalArgumentException.class, () -> new Quote(quote.code(), quote.vehicleCode(),
                quote.startAt(), quote.endAt(), quote.rentalType(), quote.driveMode(), quote.pickupMethod(),
                quote.snapshot(), quote.price(), quote.createdAt(), quote.expiresAt().plusSeconds(1)));
    }
}
