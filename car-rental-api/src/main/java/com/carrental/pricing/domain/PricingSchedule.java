package com.carrental.pricing.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Các mốc và hệ số đã chuẩn bị một lần cho khoảng thuê, tái dùng khi tính nhiều xe (BR-126).
 * @param terms dòng tính tiền không phụ thuộc giá xe
 * @param insuranceBillingDays số ngày bảo hiểm; gói giờ luôn một theo BR-231
 */
public record PricingSchedule(List<LineTerms> terms, int insuranceBillingDays) {
    /** Không giữ danh sách có thể bị bên gọi thay đổi. */
    public PricingSchedule {
        terms = List.copyOf(terms);
        if (terms.isEmpty() || insuranceBillingDays <= 0) {
            throw new IllegalArgumentException("Pricing schedule must contain lines and positive billing days.");
        }
    }

    /** Nhân giá từng xe bằng cùng bộ hệ số, làm tròn từng dòng rồi tính bảo hiểm, BR-234/236. */
    public CalculatedPrice calculate(long dailyRate, InsuranceTerms insurance) {
        var rent = calculateRent(dailyRate);
        long fee = Objects.requireNonNull(insurance, "insurance").fee(rent.rentalAmount(), insuranceBillingDays);
        return new CalculatedPrice(rent.lines(), rent.rentalAmount(), fee, Math.addExact(rent.rentalAmount(), fee));
    }

    /** BR-126: cùng các dòng như báo giá nhưng không tính bảo hiểm, tránh tràn phí không liên quan. */
    public CalculatedRent calculateRent(long dailyRate) {
        if (dailyRate <= 0 || dailyRate % 1_000 != 0) {
            throw DomainException.ruleViolation(ErrorCode.VEHICLE_INVALID_DAILY_RATE);
        }
        var lines = new ArrayList<QuoteLine>(terms.size());
        long rentalAmount = 0;
        for (var term : terms) {
            long amount = VndRounding.round(BigDecimal.valueOf(dailyRate)
                    .multiply(BigDecimal.ONE.add(term.dayPricing().uplift())).multiply(term.factor()));
            lines.add(new QuoteLine(term.type(), term.dayPricing().dayType(), term.startAt(), term.hours(),
                    dailyRate, term.dayPricing().uplift(), amount));
            rentalAmount = Math.addExact(rentalAmount, amount);
        }
        return new CalculatedRent(lines, rentalAmount);
    }

    /** Tiền thuê thuần BR-236, dùng chung cho search và báo giá, không phải response HTTP. */
    public record CalculatedRent(List<QuoteLine> lines, long rentalAmount) {
        /** Giữ các dòng bất biến để một xe không làm thay đổi kết quả của xe khác. */
        public CalculatedRent {
            lines = List.copyOf(lines);
            long sum = 0;
            for (var line : lines) sum = Math.addExact(sum, line.amount());
            if (lines.isEmpty() || sum != rentalAmount) {
                throw new IllegalArgumentException("Rental total does not match the price lines.");
            }
        }
    }

    /** Một dòng hệ số thuần, không phải bản ghi CSDL hoặc response API. */
    public record LineTerms(QuoteLineType type, Instant startAt, int hours,
            PricingParameters.DayPricing dayPricing, BigDecimal factor) {
        /** Chặn cấu trúc dòng bị thiếu hoặc hệ số không dương. */
        public LineTerms {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(dayPricing, "dayPricing");
            Objects.requireNonNull(factor, "factor");
            if (hours <= 0 || factor.signum() <= 0) throw new IllegalArgumentException("Invalid line terms.");
        }
    }
}
