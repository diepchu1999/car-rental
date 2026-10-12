package com.carrental.pricing.domain;

import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.pricing.domain.policy.DailyRentalPricingPolicy;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Duration;
import java.time.Instant;
import static com.carrental.pricing.domain.PricingTestFixtures.*;

/** Báo giá thử độc lập với CSDL local, không phải cấu hình mặc định ứng dụng. */
public final class QuoteTestFixtures {
    public static final Instant CREATED_AT = Instant.parse("2026-10-31T18:00:00Z");
    /** Tiện ích không có instance. */
    private QuoteTestFixtures() { }

    /** Fixture hai khối thứ Sáu/thứ Bảy theo ví dụ a; hạn là tham số của từng phép kiểm. */
    public static Quote quote(String code, Duration validity) {
        var base = parameters();
        var settings = new PricingParameters(base.weekendDays(), base.weekendUplift(), base.holidayUplift(),
                base.holidayCalendar(), base.insurance(), validity);
        var start = at("2026-11-13T10:00");
        var end = at("2026-11-15T10:00");
        var schedule = new DailyRentalPricingPolicy().prepare(start, end, settings, ZONE);
        return Quote.create(code, "XE-TEST01", start, end, RentalType.DAILY, DriveMode.SELF_DRIVE,
                PickupMethod.BRANCH, QuoteSnapshot.from(1_000_000, settings, schedule.insuranceBillingDays()),
                schedule.calculate(1_000_000, settings.insurance()), CREATED_AT);
    }

    /** Biểu diễn dữ liệu đã reload trong các unit test service, không sửa aggregate production. */
    public static QuoteDetail detail(Quote quote) {
        return new QuoteDetail(quote.code(), quote.vehicleCode(), quote.startAt(), quote.endAt(),
                quote.rentalType(), quote.driveMode(), quote.pickupMethod(), quote.snapshot(), quote.price(),
                quote.createdAt(), quote.expiresAt());
    }
}
