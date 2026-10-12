package com.carrental.pricing.domain;

import com.carrental.pricing.domain.policy.DailyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.HourlyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.RentalPricingPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tính tiền thật bằng domain thuần, phủ toàn bộ ví dụ a–k2 và biên BR-114/218/231/235–237. */
class PricingCalculationTest {
    /** Mỗi ví dụ kiểm tiền từng dòng, loại ngày, loại dòng, tổng tiền thuê và bảo hiểm riêng. */
    @ParameterizedTest(name = "example {0}")
    @MethodSource("examples")
    void matchesApprovedExamples(Example example) {
        var parameters = parameters();
        var result = example.policy().prepare(at(example.start()), at(example.end()), parameters, ZONE)
                .calculate(example.rate(), parameters.insurance());
        assertEquals(example.amounts(), result.lines().stream().map(QuoteLine::amount).toList());
        assertEquals(example.types(), result.lines().stream().map(QuoteLine::type).toList());
        assertEquals(example.days(), result.lines().stream().map(QuoteLine::dayType).toList());
        assertEquals(example.rentalAmount(), result.rentalAmount());
        assertEquals(example.insuranceFee(), result.insuranceFee());
        assertEquals(example.rentalAmount() + example.insuranceFee(), result.totalAmount());
        for (var line : result.lines()) {
            assertEquals(example.rate(), line.dailyRate());
            assertEquals(0, line.amount() % 1_000);
        }
    }

    /** Bốn giờ phần lẻ vẫn là dòng giờ; vượt dù một phút thành thêm ngày sau làm tròn giờ. */
    @ParameterizedTest
    @CsvSource({"24,0,1000000,1", "26,0,1400000,1", "28,0,1800000,1", "28,1,2000000,2",
            "48,0,2000000,2", "48,1,2200000,2"})
    void dailyRemainderBoundary(int hours, int minutes, long rental, int insuranceDays) {
        var start = at("2026-11-09T10:00");
        var schedule = new DailyRentalPricingPolicy().prepare(start,
                start.plus(Duration.ofHours(hours)).plus(Duration.ofMinutes(minutes)), parameters(), ZONE);
        assertEquals(rental, schedule.calculate(1_000_000, parameters().insurance()).rentalAmount());
        assertEquals(insuranceDays, schedule.insuranceBillingDays());
    }

    /** Một nano giây cũng làm tròn lên; trên tám giờ dùng dòng DAY_BLOCK, BR-114/237. */
    @ParameterizedTest
    @CsvSource({"4,0,500000,HOURLY_PACKAGE", "4,1,600000,HOURLY_PACKAGE",
            "8,0,900000,HOURLY_PACKAGE", "8,1,1000000,DAY_BLOCK"})
    void hourlyBoundaryIncludesNanoseconds(int hours, int nanos, long rental, QuoteLineType firstType) {
        var start = at("2026-11-10T08:00");
        var result = new HourlyRentalPricingPolicy().prepare(start,
                start.plus(Duration.ofHours(hours)).plusNanos(nanos), parameters(), ZONE)
                .calculate(1_000_000, parameters().insurance());
        assertEquals(rental, result.rentalAmount());
        assertEquals(firstType, result.lines().getFirst().type());
    }

    /** Gói giờ dài trên 24 giờ có đúng các dòng tiền thuê gói ngày nhưng bảo hiểm chỉ một ngày. */
    @Test
    void longHourlyRentalUsesDailyLinesAndOneInsuranceDay() {
        var parameters = parameters();
        var start = at("2026-11-10T08:00");
        var end = start.plus(Duration.ofHours(53));
        var daily = new DailyRentalPricingPolicy().prepare(start, end, parameters, ZONE)
                .calculate(1_000_000, parameters.insurance());
        var hourly = new HourlyRentalPricingPolicy().prepare(start, end, parameters, ZONE)
                .calculate(1_000_000, parameters.insurance());
        assertEquals(daily.lines(), hourly.lines());
        assertEquals(daily.rentalAmount(), hourly.rentalAmount());
        assertEquals(300_000, daily.insuranceFee());
        assertEquals(100_000, hourly.insuranceFee());
    }

    /** Giờ vượt vẫn lấy loại ngày của giờ nhận, kể cả phần vượt đã bước qua nửa đêm. */
    @Test
    void hourlyOverageKeepsPickupDayType() {
        var start = at("2026-11-13T22:00");
        var result = new HourlyRentalPricingPolicy().prepare(start, start.plus(Duration.ofHours(6)),
                parameters(), ZONE).calculate(1_000_000, parameters().insurance());
        assertEquals(700_000, result.rentalAmount());
        assertTrue(result.lines().stream().allMatch(line -> line.dayType() == DayType.WEEKDAY));
    }

    /** Không áp mức tăng của giờ trả xe khi không có dòng khối bắt đầu tại mốc đó. */
    @Test
    void dailyBlocksUseStartDatesNotReturnDate() {
        var start = at("2026-11-13T10:00");
        var result = new DailyRentalPricingPolicy().prepare(start, start.plus(Duration.ofHours(24)),
                parameters(), ZONE).calculate(1_000_000, parameters().insurance());
        assertEquals(1_000_000, result.rentalAmount());
        assertEquals(DayType.WEEKDAY, result.lines().getFirst().dayType());
    }

    /** Chuẩn bị một khoảng rồi tính nhiều giá xe, không phân loại ngày lại hoặc sửa kết quả cũ. */
    @Test
    void reusesOneImmutableScheduleForMultipleVehicles() {
        var parameters = parameters();
        var schedule = new DailyRentalPricingPolicy().prepare(at("2027-02-04T10:00"),
                at("2027-02-07T10:00"), parameters, ZONE);
        var first = schedule.calculate(855_000, parameters.insurance());
        var second = schedule.calculate(1_000_000, parameters.insurance());
        assertEquals(3_849_000, first.rentalAmount());
        assertEquals(4_500_000, second.rentalAmount());
        assertEquals(855_000, first.lines().getFirst().dailyRate());
        assertThrows(UnsupportedOperationException.class, () -> schedule.terms().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.lines().clear());
    }

    /** Khoảng không dương không tạo dòng; gói giờ chưa đủ bốn giờ không bị làm tròn thành hợp lệ. */
    @Test
    void rejectsInvalidDurationBeforePricing() {
        var start = at("2026-11-10T08:00");
        var daily = new DailyRentalPricingPolicy();
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> daily.prepare(start, start, parameters(), ZONE)).errorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> daily.prepare(start, start.minusSeconds(1), parameters(), ZONE)).errorCode());
        assertEquals(ErrorCode.RENTAL_DURATION_TOO_SHORT, assertThrows(DomainException.class,
                () -> new HourlyRentalPricingPolicy().prepare(start,
                        start.plus(Duration.ofHours(4)).minusNanos(1), parameters(), ZONE)).errorCode());
    }

    /** Giá đầu vào sai không tạo kết quả giả; tiền vượt miền BIGINT không bị tràn âm. */
    @Test
    void rejectsInvalidRateAndArithmeticOverflow() {
        var schedule = new DailyRentalPricingPolicy().prepare(at("2026-11-10T10:00"),
                at("2026-11-12T10:00"), parameters(), ZONE);
        for (long rate : new long[]{0, -1_000, 1_500}) {
            assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE, assertThrows(DomainException.class,
                    () -> schedule.calculate(rate, parameters().insurance())).errorCode());
        }
        assertThrows(ArithmeticException.class,
                () -> schedule.calculate(9_223_372_036_854_775_000L, parameters().insurance()));
    }

    /** Các số tiền đã được Tech Owner duyệt trong Task 7, không sinh kỳ vọng bằng code tính giá. */
    private static Stream<Example> examples() {
        var daily = new DailyRentalPricingPolicy();
        var hourly = new HourlyRentalPricingPolicy();
        return Stream.of(
                example("a", daily, "2026-11-13T10:00", "2026-11-15T10:00", 1_000_000,
                        List.of(1_000_000L, 1_200_000L), List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.DAY_BLOCK),
                        List.of(DayType.WEEKDAY, DayType.WEEKEND), 2_200_000, 200_000),
                example("b", daily, "2026-11-10T10:00", "2026-11-11T12:00", 1_000_000,
                        List.of(1_000_000L, 400_000L), List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.REMAINDER_HOURS),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 1_400_000, 100_000),
                example("c", daily, "2026-11-10T10:00", "2026-11-11T15:00", 1_000_000,
                        List.of(1_000_000L, 1_000_000L), List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.DAY_BLOCK),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 2_000_000, 200_000),
                example("d", daily, "2026-11-10T08:00", "2026-11-10T18:00", 1_000_000,
                        List.of(1_000_000L), List.of(QuoteLineType.DAY_BLOCK), List.of(DayType.WEEKDAY), 1_000_000, 100_000),
                example("e", daily, "2026-11-10T10:00", "2026-11-11T10:01", 1_000_000,
                        List.of(1_000_000L, 200_000L), List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.REMAINDER_HOURS),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 1_200_000, 100_000),
                example("f", hourly, "2026-11-10T08:00", "2026-11-10T13:00", 1_000_000,
                        List.of(500_000L, 100_000L), List.of(QuoteLineType.HOURLY_PACKAGE, QuoteLineType.OVERAGE_HOURS),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 600_000, 100_000),
                example("g", hourly, "2026-11-10T08:00", "2026-11-10T16:00", 1_000_000,
                        List.of(500_000L, 400_000L), List.of(QuoteLineType.HOURLY_PACKAGE, QuoteLineType.OVERAGE_HOURS),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 900_000, 100_000),
                example("h", hourly, "2026-11-10T08:00", "2026-11-10T17:00", 1_000_000,
                        List.of(1_000_000L), List.of(QuoteLineType.DAY_BLOCK), List.of(DayType.WEEKDAY), 1_000_000, 100_000),
                example("i", hourly, "2026-11-14T08:00", "2026-11-14T13:00", 1_000_000,
                        List.of(600_000L, 120_000L), List.of(QuoteLineType.HOURLY_PACKAGE, QuoteLineType.OVERAGE_HOURS),
                        List.of(DayType.WEEKEND, DayType.WEEKEND), 720_000, 100_000),
                example("j", daily, "2026-11-23T10:00", "2026-11-25T10:00", 1_000_000,
                        List.of(1_000_000L, 1_500_000L), List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.DAY_BLOCK),
                        List.of(DayType.WEEKDAY, DayType.HOLIDAY), 2_500_000, 200_000),
                example("k", daily, "2027-02-04T10:00", "2027-02-07T10:00", 855_000,
                        List.of(1_283_000L, 1_283_000L, 1_283_000L),
                        List.of(QuoteLineType.DAY_BLOCK, QuoteLineType.DAY_BLOCK, QuoteLineType.DAY_BLOCK),
                        List.of(DayType.HOLIDAY, DayType.HOLIDAY, DayType.HOLIDAY), 3_849_000, 300_000),
                example("k2", hourly, "2026-11-10T08:00", "2026-11-10T15:00", 855_000,
                        List.of(428_000L, 257_000L), List.of(QuoteLineType.HOURLY_PACKAGE, QuoteLineType.OVERAGE_HOURS),
                        List.of(DayType.WEEKDAY, DayType.WEEKDAY), 685_000, 100_000));
    }

    /** Giữ dữ liệu ví dụ rõ ràng, không lặp constructor dài trong từng phép kiểm. */
    private static Example example(String name, RentalPricingPolicy policy, String start, String end, long rate,
            List<Long> amounts, List<QuoteLineType> types, List<DayType> days, long rental, long insurance) {
        return new Example(name, policy, start, end, rate, amounts, types, days, rental, insurance);
    }

    /** Dữ liệu kỳ vọng độc lập với hiện thực, chỉ dùng trong test. */
    private record Example(String name, RentalPricingPolicy policy, String start, String end, long rate,
            List<Long> amounts, List<QuoteLineType> types, List<DayType> days, long rentalAmount, long insuranceFee) {
        /** Tên ví dụ hiển thị trong log test tham số hóa. */
        @Override public String toString() { return name; }
    }
}
