package com.carrental.booking.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Khẳng định BR-201/236 bằng các kết quả VND cụ thể, gồm biên làm tròn và tràn số. */
class BookingAmountsTest {
    /** Tổng gồm bảo hiểm là cơ sở tính cọc, không chỉ tiền thuê. */
    @Test
    void depositIncludesInsuranceAndBalanceCompletesTotal() {
        var result = BookingAmounts.calculate(1_000_000, 100_000, new BigDecimal("0.30"));
        assertEquals(1_100_000, result.totalAmount());
        assertEquals(330_000, result.deposit());
        assertEquals(770_000, result.balance());
        assertEquals(new BigDecimal("0.30"), result.depositRatio());
    }

    /** Dưới 500 làm tròn xuống, đúng 500 làm tròn lên; phần còn lại không làm tròn độc lập. */
    @ParameterizedTest
    @CsvSource({"1001000,300000,701000", "1005000,302000,703000", "1009000,303000,706000", "1000,0,1000"})
    void roundsDepositAndSubtractsBalance(long total, long deposit, long balance) {
        var result = BookingAmounts.calculate(total, 0, new BigDecimal("0.30"));
        assertEquals(deposit, result.deposit());
        assertEquals(balance, result.balance());
    }

    /** Khôi phục giữ tỷ lệ đã lưu; không áp 30% hiện tại lên bản sao có tỷ lệ khác. */
    @Test
    void restoresUsingFrozenRatio() {
        var restored = new BookingAmounts(1_000_000, 100_000, 1_100_000, new BigDecimal("0.40"), 440_000, 660_000);
        assertEquals(440_000, restored.deposit());
    }

    /** Không thể dựng snapshot có tổng, cọc hoặc phần còn lại lệch nhau. */
    @Test
    void rejectsTamperedSavedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> new BookingAmounts(1_000_000, 100_000,
                1_100_000, new BigDecimal("0.30"), 300_000, 800_000));
        assertThrows(IllegalArgumentException.class, () -> new BookingAmounts(1_000_000, 100_000,
                1_100_000, new BigDecimal("0.30"), 330_000, 771_000));
        assertThrows(IllegalArgumentException.class, () -> BookingAmounts.calculate(1_000_001, 0, new BigDecimal("0.30")));
        assertThrows(IllegalArgumentException.class, () -> BookingAmounts.calculate(1_000_000, -1_000, new BigDecimal("0.30")));
        assertThrows(IllegalArgumentException.class, () -> BookingAmounts.calculate(1_000_000, 0, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> BookingAmounts.calculate(1_000_000, 0, new BigDecimal("1.01")));
    }

    /** Cộng tràn bị từ chối thay vì tạo số tiền âm hoặc mất chính xác qua double. */
    @Test
    void rejectsOverflowWithoutLosingPrecisionForLargeValidAmounts() {
        assertThrows(ArithmeticException.class, () -> BookingAmounts.calculate(Long.MAX_VALUE, 1_000, new BigDecimal("0.30")));
        var result = BookingAmounts.calculate(9_000_000_000_000_000_000L, 0, new BigDecimal("0.30"));
        assertEquals(2_700_000_000_000_000_000L, result.deposit());
        assertEquals(6_300_000_000_000_000_000L, result.balance());
    }
}
