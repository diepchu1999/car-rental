package com.carrental.pricing.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * Lịch lễ tường minh, không tự suy ngày nghỉ liền kề (BR-218).
 * @param declaredThrough ngày cuối đã khai báo
 * @param dates mọi ngày trong kỳ nghỉ lễ đã duyệt
 */
public record HolidayCalendar(LocalDate declaredThrough, Set<LocalDate> dates) {
    /** Sao chép bất biến và từ chối ngày nằm ngoài hạn khai báo. */
    public HolidayCalendar {
        Objects.requireNonNull(declaredThrough, "declaredThrough");
        dates = Set.copyOf(dates);
        if (dates.stream().anyMatch(date -> date.isAfter(declaredThrough))) {
            throw new IllegalArgumentException("Holiday date exceeds declaredThrough.");
        }
    }

    /** Kiểm ngày lịch giờ trả xe, gồm cả ngày cuối; không dùng endExclusive trừ một nano giây. */
    public void requireCoverage(Instant endAt, ZoneId zone) {
        if (Objects.requireNonNull(endAt, "endAt").atZone(Objects.requireNonNull(zone, "zone"))
                .toLocalDate().isAfter(declaredThrough)) {
            throw DomainException.ruleViolation(ErrorCode.HOLIDAY_CALENDAR_NOT_DECLARED);
        }
    }
}
