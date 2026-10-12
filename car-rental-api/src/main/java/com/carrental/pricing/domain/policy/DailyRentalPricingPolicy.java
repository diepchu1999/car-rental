package com.carrental.pricing.domain.policy;

import com.carrental.pricing.domain.PricingParameters;
import com.carrental.pricing.domain.PricingSchedule;
import com.carrental.pricing.domain.QuoteLineType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;

/** Gói ngày theo khối 24 giờ, phần lẻ tối đa bốn giờ tính 20% mỗi giờ (BR-235). */
public final class DailyRentalPricingPolicy implements RentalPricingPolicy {
    private static final int HOURS_PER_DAY = 24; // BR-235: khối 24 giờ thực tế.
    private static final int MAX_REMAINDER_HOURS = 4; // BR-235.
    private static final BigDecimal REMAINDER_FACTOR = new BigDecimal("0.2"); // BR-235.

    /** Chuẩn bị dòng và loại ngày từng khối; kiểm lịch bằng ngày giờ trả thực tế (BR-218). */
    @Override
    public PricingSchedule prepare(Instant startAt, Instant endAt, PricingParameters parameters, ZoneId zone) {
        int hours = BillingHours.between(startAt, endAt);
        Objects.requireNonNull(parameters, "parameters").holidayCalendar().requireCoverage(endAt, zone);
        int fullDays = hours / HOURS_PER_DAY;
        int remainder = hours % HOURS_PER_DAY;
        int blocks = fullDays == 0 ? 1 : fullDays;
        var terms = new ArrayList<PricingSchedule.LineTerms>();
        for (int day = 0; day < blocks; day++) {
            Instant at = startAt.plus(Duration.ofHours((long) day * HOURS_PER_DAY));
            terms.add(dayBlock(at, parameters, zone));
        }
        if (fullDays > 0 && remainder > 0) {
            Instant at = startAt.plus(Duration.ofHours((long) fullDays * HOURS_PER_DAY));
            if (remainder <= MAX_REMAINDER_HOURS) {
                terms.add(new PricingSchedule.LineTerms(QuoteLineType.REMAINDER_HOURS, at, remainder,
                        parameters.dayAt(at, zone), REMAINDER_FACTOR.multiply(BigDecimal.valueOf(remainder))));
            } else {
                terms.add(dayBlock(at, parameters, zone));
                blocks++;
            }
        }
        return new PricingSchedule(terms, blocks);
    }

    /** Một khối một dòng, không gộp các ngày có cùng mức tăng trước khi làm tròn (BR-236). */
    private static PricingSchedule.LineTerms dayBlock(Instant at, PricingParameters parameters, ZoneId zone) {
        return new PricingSchedule.LineTerms(QuoteLineType.DAY_BLOCK, at, HOURS_PER_DAY,
                parameters.dayAt(at, zone), BigDecimal.ONE);
    }
}
