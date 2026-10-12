package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Khóa BR-234 tại domain, không phụ thuộc JSON hoặc PostgreSQL. */
class DailyRateTest {
    /** Giá hợp lệ được giữ nguyên, kể cả giá lớn vượt miền int. */
    @ParameterizedTest
    @ValueSource(longs = {1000, 855000, 1000000, 3000000000L, 9223372036854775000L})
    void preservesValidRate(long amount) {
        assertEquals(amount, DailyRate.from(BigDecimal.valueOf(amount)).amount());
    }

    /** Thiếu, không dương, không chia hết, có phần lẻ và tràn BIGINT đều cùng lỗi BR-234. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1000", "1", "999", "1500", "1000.1", "9223372036854776000"})
    void rejectsInvalidInputWithoutRounding(String value) {
        var failure = assertThrows(DomainException.class,
                () -> DailyRate.from(value == null ? null : new BigDecimal(value)));
        assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Constructor trực tiếp không được bỏ qua bất biến để lách factory. */
    @ParameterizedTest
    @ValueSource(longs = {-1000, 0, 1500})
    void validatesDirectConstruction(long amount) {
        assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE,
                assertThrows(DomainException.class, () -> new DailyRate(amount)).errorCode());
    }

    /** Mapper phải giữ null khi chưa nhập giá, không tự đặt giá mẫu. */
    @Test
    void restoresNullableRate() {
        assertNull(DailyRate.ofNullable(null));
        assertEquals(new DailyRate(855_000), DailyRate.ofNullable(855_000L));
    }
}
