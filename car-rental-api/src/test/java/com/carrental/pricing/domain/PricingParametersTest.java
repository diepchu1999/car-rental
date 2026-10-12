package com.carrental.pricing.domain;

import com.carrental.pricing.domain.policy.DailyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.HourlyRentalPricingPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm lịch lễ, mức tăng thực sự áp dụng và tính bất biến BR-218/225. */
class PricingParametersTest {
    /** Cuối tuần trùng lễ lấy mức cao hơn; bằng nhau chọn HOLIDAY, không cộng hoặc nhân dồn. */
    @ParameterizedTest
    @CsvSource({"0.2,0.5,HOLIDAY,1500000", "0.8,0.5,WEEKEND,1800000", "0.5,0.5,HOLIDAY,1500000"})
    void overlapUsesWinningUpliftAndLabel(String weekend, String holiday, DayType expected, long amount) {
        var base = parameters();
        var parameters = new PricingParameters(base.weekendDays(), new BigDecimal(weekend),
                new BigDecimal(holiday), base.holidayCalendar(), base.insurance(), base.quoteValidity());
        var start = at("2027-02-06T10:00");
        var result = new DailyRentalPricingPolicy().prepare(start, start.plus(Duration.ofHours(24)),
                parameters, ZONE).calculate(1_000_000, parameters.insurance());
        assertEquals(expected, result.lines().getFirst().dayType());
        assertEquals(amount, result.rentalAmount());
        var winning = new BigDecimal(weekend).max(new BigDecimal(holiday));
        assertEquals(0, winning.compareTo(result.lines().getFirst().uplift()));
    }

    /** Ngày trước ngày lễ không tự trở thành ngày cao điểm; cuối tuần do tập cấu hình quyết định. */
    @Test
    void usesExplicitCalendarAndConfigurableWeekendDays() {
        assertEquals(DayType.WEEKDAY, parameters().dayAt(at("2026-11-23T10:00"), ZONE).dayType());
        var base = parameters();
        var parameters = new PricingParameters(Set.of(DayOfWeek.FRIDAY), base.weekendUplift(),
                base.holidayUplift(), base.holidayCalendar(), base.insurance(), base.quoteValidity());
        assertEquals(DayType.WEEKEND, parameters.dayAt(at("2026-11-13T10:00"), ZONE).dayType());
        assertEquals(DayType.WEEKDAY, parameters.dayAt(at("2026-11-14T10:00"), ZONE).dayType());
    }

    /** Ngày giờ trả Việt Nam là biên, kể cả endExclusive đúng nửa đêm ngày chưa khai báo. */
    @Test
    void coverageUsesVietnamReturnDateIncludingMidnight() {
        var calendar = parameters().holidayCalendar();
        assertDoesNotThrow(() -> calendar.requireCoverage(Instant.parse("2027-12-31T16:59:59.999999999Z"), ZONE));
        var failure = assertThrows(DomainException.class,
                () -> calendar.requireCoverage(Instant.parse("2027-12-31T17:00:00Z"), ZONE));
        assertEquals(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED, failure.errorCode());
    }

    /** Cả gói giờ/ngày đều từ chối khoảng vượt lịch, kể cả khối đầu còn trong ngày đã khai báo. */
    @Test
    void bothPoliciesRejectUndeclaredReturnDate() {
        var start = at("2027-12-31T20:00");
        var end = at("2028-01-01T04:00");
        assertEquals(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED, assertThrows(DomainException.class,
                () -> new DailyRentalPricingPolicy().prepare(start, end, parameters(), ZONE)).errorCode());
        assertEquals(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED, assertThrows(DomainException.class,
                () -> new HourlyRentalPricingPolicy().prepare(start, end, parameters(), ZONE)).errorCode());
    }

    /** Bộ tham số sao chép tập; thay tập đầu vào không thể âm thầm đổi kết quả đã chuẩn bị. */
    @Test
    void parametersAndCalendarAreImmutable() {
        var days = new HashSet<>(Set.of(DayOfWeek.SATURDAY));
        var dates = new HashSet<>(Set.of(LocalDate.of(2026, 11, 24)));
        var base = parameters();
        var parameters = new PricingParameters(days, base.weekendUplift(), base.holidayUplift(),
                new HolidayCalendar(LocalDate.of(2027, 12, 31), dates), base.insurance(), base.quoteValidity());
        days.clear();
        dates.clear();
        assertEquals(Set.of(DayOfWeek.SATURDAY), parameters.weekendDays());
        assertEquals(Set.of(LocalDate.of(2026, 11, 24)), parameters.holidayCalendar().dates());
        assertThrows(UnsupportedOperationException.class, () -> parameters.weekendDays().clear());
        assertThrows(UnsupportedOperationException.class, () -> parameters.holidayCalendar().dates().clear());
    }

    /** Ngày lễ ngoài hạn bị chặn tại domain, không chỉ dựa vào adapter cấu hình. */
    @Test
    void rejectsHolidayBeyondDeclaredThrough() {
        assertThrows(IllegalArgumentException.class, () -> new HolidayCalendar(LocalDate.of(2027, 12, 31),
                Set.of(LocalDate.of(2028, 1, 1))));
    }
}
