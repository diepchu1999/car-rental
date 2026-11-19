package com.carrental.booking.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Snapshot giữ đúng dòng tiền và điều kiện đã áp; không mang lịch lễ hoặc giá trị quyết toán giả. */
class BookingSnapshotTest {
    /** Danh sách đầu vào thay đổi không làm đổi đơn; danh sách bên trong không sửa được. */
    @Test
    void ownsImmutableCopyOfPriceLines() {
        var lines = new ArrayList<>(List.of(line()));
        var result = new BookingPriceSnapshot(1_000_000, lines, "PER_BILLING_DAY", new BigDecimal("100000"), 1,
                price().amounts());
        lines.clear();
        assertEquals(1, result.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> result.lines().clear());
    }

    /** Không cho header tổng đúng nhưng các dòng thiếu hoặc có giá ngày khác. */
    @Test
    void rejectsInconsistentLines() {
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceSnapshot(1_000_000, List.of(),
                "PER_BILLING_DAY", new BigDecimal("100000"), 1, price().amounts()));
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceSnapshot(2_000_000, List.of(line()),
                "PER_BILLING_DAY", new BigDecimal("100000"), 1, price().amounts()));
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceSnapshot(1_000_000, List.of(line(), line()),
                "PER_BILLING_DAY", new BigDecimal("100000"), 1, price().amounts()));
    }

    /** BR-231: tham số bảo hiểm phải dương; mức cố định nguyên VND, tỷ lệ không có trần. */
    @Test
    void validatesInsuranceSourceValueRatherThanRoundedFee() {
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceSnapshot(1_000_000, List.of(line()),
                "PER_BILLING_DAY", BigDecimal.ZERO, 1, price().amounts()));
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceSnapshot(1_000_000, List.of(line()),
                "PER_BILLING_DAY", new BigDecimal("0.1"), 1, price().amounts()));
        assertDoesNotThrow(() -> new BookingPriceSnapshot(1_000_000, List.of(line()),
                "PERCENT_OF_RENT", new BigDecimal("1.5"), 1, price().amounts()));
    }

    /** Từ vựng dòng và số giờ khớp BR-114/235; không gán loại lạ hoặc số giờ sai. */
    @ParameterizedTest
    @CsvSource({"DAY_BLOCK,23", "HOURLY_PACKAGE,5", "REMAINDER_HOURS,0", "OVERAGE_HOURS,5", "UNKNOWN,24"})
    void rejectsInvalidLineShape(String type, int hours) {
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceLine(type, "WEEKDAY", START,
                hours, 1_000_000, BigDecimal.ZERO, 1_000_000));
    }

    /** Mức tăng của ngày thường phải bằng không, ngày lễ được giữ nguyên mức tăng đã áp. */
    @Test
    void preservesAppliedDayType() {
        assertThrows(IllegalArgumentException.class, () -> new BookingPriceLine("DAY_BLOCK", "WEEKDAY", START,
                24, 1_000_000, new BigDecimal("0.2"), 1_200_000));
        assertEquals("HOLIDAY", new BookingPriceLine("DAY_BLOCK", "HOLIDAY", START,
                24, 1_000_000, new BigDecimal("0.2"), 1_200_000).dayType());
    }

    /** Không biến sáu tháng lịch thành 180 ngày và không bịa thời lượng tối thiểu cho gói ngày. */
    @Test
    void preservesCalendarMonthsAndAbsenceOfMinimumDuration() {
        assertEquals(Period.ofMonths(6), terms().maximumAdvance());
        assertTrue(terms().minimumDuration().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new BookingRentalTerms(Duration.ofHours(2), Optional.empty(),
                LocalTime.of(7, 0), LocalTime.of(22, 0), Duration.ofHours(1), Period.ofDays(180)));
        assertThrows(IllegalArgumentException.class, () -> new BookingRentalTerms(Duration.ZERO, Optional.empty(),
                LocalTime.of(7, 0), LocalTime.of(22, 0), Duration.ofHours(1), Period.ofMonths(6)));
    }
}
