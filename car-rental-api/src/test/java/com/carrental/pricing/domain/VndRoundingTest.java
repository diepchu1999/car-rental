package com.carrental.pricing.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm BR-236 bằng các giá trị sát biên, không lấy kỳ vọng từ chính thuật toán. */
class VndRoundingTest {
    /** Dưới 500 xuống, từ 500 lên; tiền sau phép nhân không bị làm tròn trước khi gom dòng. */
    @ParameterizedTest
    @CsvSource({"0,0", "499.999,0", "500,1000", "1499.999,1000", "1500,2000",
            "427500,428000", "256500,257000", "1282500,1283000"})
    void roundsEachLineHalfUp(String amount, long expected) {
        assertEquals(expected, VndRounding.round(new BigDecimal(amount)));
    }

    /** Không cho tiền âm hoặc cắt giá trị vượt BIGINT, kể cả làm tròn đã vượt giới hạn. */
    @Test
    void rejectsNegativeAndOverflowingAmounts() {
        assertThrows(IllegalArgumentException.class, () -> VndRounding.round(new BigDecimal("-1")));
        assertThrows(ArithmeticException.class, () -> VndRounding.round(new BigDecimal("9223372036854775807")));
    }
}
