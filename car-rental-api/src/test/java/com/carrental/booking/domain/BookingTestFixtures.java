package com.carrental.booking.domain;

import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.util.List;
import java.util.Optional;

/** Dữ liệu thử cố định, không phải cấu hình nghiệp vụ hoặc dữ liệu khách thật. */
public final class BookingTestFixtures {
    public static final Instant CREATED = Instant.parse("2026-11-10T03:00:00Z");
    public static final Instant START = Instant.parse("2026-11-13T03:00:00Z");
    public static final Instant END = START.plus(Duration.ofDays(1));

    /** Tiện ích không có instance. */
    private BookingTestFixtures() { }

    /** Một ngày thuê một triệu, bảo hiểm một trăm nghìn, cọc 330 nghìn (BR-201). */
    public static BookingPriceSnapshot price() {
        return new BookingPriceSnapshot(1_000_000, List.of(line()), "PER_BILLING_DAY",
                new BigDecimal("100000"), 1, BookingAmounts.calculate(1_000_000, 100_000, new BigDecimal("0.30")));
    }

    /** Dòng báo giá đã chốt trước khi tạo đơn; không cần gọi module pricing trong domain test. */
    public static BookingPriceLine line() {
        return new BookingPriceLine("DAY_BLOCK", "WEEKDAY", START, 24, 1_000_000, BigDecimal.ZERO, 1_000_000);
    }

    /** Điều kiện gói ngày trong fixture; cửa sổ xa nhất giữ tháng lịch. */
    public static BookingRentalTerms terms() {
        return new BookingRentalTerms(Duration.ofHours(2), Optional.empty(), LocalTime.of(7, 0),
                LocalTime.of(22, 0), Duration.ofHours(1), Period.ofMonths(6));
    }

    /** Hạn bất thường 37 phút chứng minh domain không tự đóng cứng lại thành một giờ. */
    public static Booking booking() {
        return Booking.create("DT-2611-TEST01", "TK-OWNER1", "BG-2611-TEST01", "XE-TEST01", "CN-TEST01",
                "KL-TEST01", START, END, RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH,
                price(), terms(), CREATED.plusSeconds(2_220), CREATED);
    }
}
