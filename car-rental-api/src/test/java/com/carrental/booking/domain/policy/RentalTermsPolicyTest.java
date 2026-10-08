package com.carrental.booking.domain.policy;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm biên chính sách bằng đồng hồ cố định, không cần Spring hoặc database. */
class RentalTermsPolicyTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant AT = local("2026-10-05T08:00:00");

    /** BR-113: đúng 4 giờ và dài hơn 1 nano giây đều hợp lệ. */
    @ParameterizedTest
    @ValueSource(longs = {0L, 1L})
    void acceptsHourlyMinimumIncludingBoundary(long extraNanos) {
        Instant start = local("2026-10-05T10:00:00");
        assertDoesNotThrow(() -> hourly().validate(start, start.plus(Duration.ofHours(4)).plusNanos(extraNanos), AT, ZONE));
    }

    /** BR-113: kể cả chỉ thiếu một nano giây cũng phải bị từ chối. */
    @ParameterizedTest
    @ValueSource(longs = {1L, 3_600_000_000_000L})
    void rejectsHourlyDurationBelowMinimum(long missingNanos) {
        Instant start = local("2026-10-05T10:00:00");
        assertRule(ErrorCode.RENTAL_DURATION_TOO_SHORT,
                () -> hourly().validate(start, start.plus(Duration.ofHours(4)).minusNanos(missingNanos), AT, ZONE));
    }

    /** Không áp tối thiểu 4 hoặc 24 giờ vào gói không có quy tắc tối thiểu. */
    @Test
    void doesNotInventMinimumForDailyTerms() {
        Instant start = local("2026-10-05T10:00:00");
        assertDoesNotThrow(() -> daily(1).validate(start, start.plusNanos(1), AT, ZONE));
    }

    /** BR-119: gồm hai biên, cho qua đêm và không giới hạn giờ của phần đệm. */
    @ParameterizedTest
    @CsvSource({
            "2026-10-06T06:00:00,2026-10-06T23:00:00",
            "2026-10-06T23:00:00,2026-10-07T06:00:00",
            "2026-10-06T20:00:00,2026-10-06T23:00:00"
    })
    void acceptsOpeningBoundariesAndOvernightPeriods(String start, String end) {
        assertDoesNotThrow(() -> daily(1).validate(local(start), local(end), AT, ZONE));
        assertEquals(Duration.ofHours(2), daily(1).turnaroundBuffer());
    }

    /** BR-119: kiểm độc lập giờ nhận và trả, không làm tròn thời điểm sát biên. */
    @ParameterizedTest
    @CsvSource({
            "2026-10-06T05:59:59.999999999,2026-10-06T10:00:00",
            "2026-10-06T23:00:00.000000001,2026-10-07T10:00:00",
            "2026-10-06T10:00:00,2026-10-07T05:59:59.999999999",
            "2026-10-06T10:00:00,2026-10-06T23:00:00.000000001"
    })
    void rejectsEitherEndpointOutsideBranchHours(String start, String end) {
        assertRule(ErrorCode.OUTSIDE_BRANCH_HOURS,
                () -> daily(1).validate(local(start), local(end), AT, ZONE));
    }

    /** BR-121: đúng mốc báo trước 1/3 giờ và sau mốc đó được chấp nhận. */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void acceptsMinimumAdvanceBoundary(int hours) {
        Instant start = AT.plus(Duration.ofHours(hours));
        assertDoesNotThrow(() -> daily(hours).validate(start, start.plusSeconds(60), AT, ZONE));
        assertDoesNotThrow(() -> daily(hours).validate(start.plusNanos(1), start.plusSeconds(60), AT, ZONE));
    }

    /** BR-121: thiếu 1 nano giây báo trước bị từ chối ở cả hai cách nhận. */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void rejectsPickupBeforeMinimumAdvance(int hours) {
        Instant start = AT.plus(Duration.ofHours(hours)).minusNanos(1);
        assertRule(ErrorCode.BOOKING_WINDOW_VIOLATION,
                () -> daily(hours).validate(start, start.plusSeconds(60), AT, ZONE));
    }

    /** BR-121: 6 tháng lịch xử lý cuối tháng và năm nhuận, không quy đổi thành 180 ngày. */
    @ParameterizedTest
    @CsvSource({
            "2026-08-31T10:00:00,2027-02-28T10:00:00",
            "2023-08-31T10:00:00,2024-02-29T10:00:00",
            "2026-03-01T10:00:00,2026-09-01T10:00:00"
    })
    void usesSixCalendarMonthsIncludingBoundary(String now, String latest) {
        Instant at = local(now);
        Instant start = local(latest);
        assertDoesNotThrow(() -> daily(1).validate(start, start.plusSeconds(60), at, ZONE));
        assertDoesNotThrow(() -> daily(1).validate(start.minusNanos(1), start.plusSeconds(60), at, ZONE));
        assertRule(ErrorCode.BOOKING_WINDOW_VIOLATION,
                () -> daily(1).validate(start.plusNanos(1), start.plusSeconds(60), at, ZONE));
    }

    /** BR-121 áp mốc xa nhất lên giờ nhận, không ép giờ trả phải nằm trước mốc đó. */
    @Test
    void permitsReturnAfterLatestPickupBoundary() {
        Instant at = local("2026-08-31T10:00:00");
        assertDoesNotThrow(() -> daily(1).validate(local("2027-02-28T10:00:00"),
                local("2027-03-02T10:00:00"), at, ZONE));
    }

    /** BR-119 dùng múi giờ được truyền, không dùng UTC hoặc múi giờ mặc định JVM. */
    @Test
    void usesBusinessZoneForBranchHours() {
        Instant start = Instant.parse("2026-10-06T00:00:00Z");
        Instant end = start.plus(Duration.ofHours(4));
        assertDoesNotThrow(() -> hourly().validate(start, end, AT, ZONE));
        assertRule(ErrorCode.OUTSIDE_BRANCH_HOURS,
                () -> hourly().validate(start, end, AT, ZoneOffset.UTC));
    }

    /** Khoảng rỗng và đảo chiều là lỗi đầu vào, không phải lỗi chính sách thuê. */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositivePeriod(long seconds) {
        Instant start = local("2026-10-05T10:00:00");
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> daily(1).validate(start, start.plusSeconds(seconds), AT, ZONE));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Instant vượt phạm vi lịch địa phương được báo đầu vào sai thay vì lỗi hệ thống. */
    @Test
    void rejectsUnrepresentableLocalDates() {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> daily(1).validate(Instant.MAX.minusSeconds(60), Instant.MAX, AT, ZONE));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }

    /** Tạo policy giờ đã phân giải để domain không cần import RentalType. */
    private static RentalTermsPolicy hourly() {
        return new ResolvedRentalTermsPolicy(Duration.ofHours(1), Optional.of(Duration.ofHours(4)),
                LocalTime.of(6, 0), LocalTime.of(23, 0), Duration.ofHours(1), Period.ofMonths(6));
    }

    /** Tạo policy không có thời lượng tối thiểu, với cửa sổ báo trước cần thử. */
    private static RentalTermsPolicy daily(int advanceHours) {
        return new ResolvedRentalTermsPolicy(Duration.ofHours(2), Optional.empty(),
                LocalTime.of(6, 0), LocalTime.of(23, 0), Duration.ofHours(advanceHours), Period.ofMonths(6));
    }

    /** Chuyển thời gian fixture theo múi giờ Việt Nam để test không phụ thuộc máy chạy. */
    private static Instant local(String text) {
        return LocalDateTime.parse(text).atZone(ZONE).toInstant();
    }

    /** Khẳng định đúng mã nghiệp vụ và nhóm RULE_VIOLATION, không chỉ có exception. */
    private static void assertRule(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(code, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }
}
