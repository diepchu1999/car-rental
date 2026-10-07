package com.carrental.booking.api;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm lắp ráp bean và đường đi Directory → adapter → use case → policy, không cần database. */
class RentalTermsDirectoryTest {
    private AnnotationConfigApplicationContext context;
    private RentalTermsDirectory directory;
    private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");

    /** Chỉ quét module booking và cấp đồng hồ cố định, không khởi động server hay Testcontainers. */
    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(Clock.class, () -> Clock.fixed(AT, ZoneId.of("Asia/Ho_Chi_Minh")));
        context.scan("com.carrental.booking");
        context.refresh();
        directory = context.getBean(RentalTermsDirectory.class);
    }

    /** Đóng context riêng sau mỗi test. */
    @AfterEach
    void tearDown() {
        if (context != null) { context.close(); }
    }

    /** BR-116: cổng công khai trả đệm một giờ và giữ điều kiện tối thiểu gói giờ. */
    @Test
    void exposesHourlyTermsThroughPublicContract() {
        Instant start = AT.plus(Duration.ofHours(2));
        RentalTerms terms = directory.getTerms(RentalType.HOURLY, PickupMethod.BRANCH,
                start, start.plus(Duration.ofHours(4)));
        assertEquals(new RentalTerms(Duration.ofHours(1), Optional.of(Duration.ofHours(4)),
                LocalTime.of(6, 0), LocalTime.of(23, 0), Duration.ofHours(1), Period.ofMonths(6)), terms);
    }

    /** BR-113: lỗi từ domain đi nguyên vẹn qua cổng cross-module, không trả kết quả rỗng. */
    @Test
    void propagatesMinimumDurationViolation() {
        Instant start = AT.plus(Duration.ofHours(2));
        assertRule(ErrorCode.RENTAL_DURATION_TOO_SHORT,
                () -> directory.getTerms(RentalType.HOURLY, PickupMethod.BRANCH, start, start.plusSeconds(10_800)));
    }

    /** BR-121: giao tận nơi cần 3 giờ; bộ điều kiện chưa có nghĩa là tính năng search đã mở. */
    @Test
    void appliesDeliveryAdvanceWithoutEnablingSearchFeature() {
        Instant tooEarly = AT.plus(Duration.ofHours(2));
        assertRule(ErrorCode.BOOKING_WINDOW_VIOLATION,
                () -> directory.getTerms(RentalType.DAILY, PickupMethod.DELIVERY, tooEarly, tooEarly.plusSeconds(60)));
        Instant start = AT.plus(Duration.ofHours(3));
        assertEquals(Duration.ofHours(3), directory.getTerms(RentalType.DAILY, PickupMethod.DELIVERY,
                start, start.plusSeconds(60)).minimumAdvance());
    }

    /** BR-119: cổng công khai thực sự kiểm giờ trả, không chỉ chọn cấu hình gói. */
    @Test
    void rejectsReturnOutsideBranchHours() {
        Instant start = AT.plus(Duration.ofHours(2));
        Instant end = Instant.parse("2026-10-05T16:00:00.000000001Z");
        assertRule(ErrorCode.OUTSIDE_BRANCH_HOURS,
                () -> directory.getTerms(RentalType.DAILY, PickupMethod.BRANCH, start, end));
    }

    /** BR-125: lỗi thiếu trường được query chặn và truyền rõ qua adapter. */
    @Test
    void rejectsMissingInputThroughDirectory() {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> directory.getTerms(null, PickupMethod.BRANCH, AT, AT.plusSeconds(60)));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals("rentalType is required.", failure.getMessage());
    }

    /** Kiểm đúng mã và nhóm lỗi nghiệp vụ, không chỉ bất kỳ exception nào. */
    private static void assertRule(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(code, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }
}
