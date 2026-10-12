package com.carrental.pricing.domain.policy;

import com.carrental.pricing.domain.PricingParameters;
import com.carrental.pricing.domain.PricingSchedule;
import com.carrental.pricing.domain.QuoteLineType;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;

/** Gói bốn giờ 50%, giờ vượt 10%; trên tám giờ dùng đúng dòng gói ngày (BR-114). */
public final class HourlyRentalPricingPolicy implements RentalPricingPolicy {
    private static final int PACKAGE_HOURS = 4; // BR-113/114.
    private static final int DAILY_CAP_HOURS = 8; // BR-114.
    private static final BigDecimal PACKAGE_FACTOR = new BigDecimal("0.5"); // BR-114.
    private static final BigDecimal OVERAGE_FACTOR = new BigDecimal("0.1"); // BR-114.
    private final DailyRentalPricingPolicy daily = new DailyRentalPricingPolicy();

    /** Không cộng gói giả cho khoảng dài; bảo hiểm gói giờ luôn một ngày theo BR-231. */
    @Override
    public PricingSchedule prepare(Instant startAt, Instant endAt, PricingParameters parameters, ZoneId zone) {
        int hours = BillingHours.between(startAt, endAt);
        if (Duration.between(startAt, endAt).compareTo(Duration.ofHours(PACKAGE_HOURS)) < 0) {
            throw DomainException.ruleViolation(ErrorCode.RENTAL_DURATION_TOO_SHORT);
        }
        Objects.requireNonNull(parameters, "parameters");
        if (hours > DAILY_CAP_HOURS) {
            return new PricingSchedule(daily.prepare(startAt, endAt, parameters, zone).terms(), 1);
        }
        parameters.holidayCalendar().requireCoverage(endAt, zone);
        var day = parameters.dayAt(startAt, zone);
        var terms = new ArrayList<PricingSchedule.LineTerms>();
        terms.add(new PricingSchedule.LineTerms(QuoteLineType.HOURLY_PACKAGE, startAt, PACKAGE_HOURS,
                day, PACKAGE_FACTOR));
        if (hours > PACKAGE_HOURS) {
            int overage = hours - PACKAGE_HOURS;
            terms.add(new PricingSchedule.LineTerms(QuoteLineType.OVERAGE_HOURS,
                    startAt.plus(Duration.ofHours(PACKAGE_HOURS)), overage, day,
                    OVERAGE_FACTOR.multiply(BigDecimal.valueOf(overage))));
        }
        return new PricingSchedule(terms, 1);
    }
}
