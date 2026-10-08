package com.carrental.booking.domain.policy;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * Áp các điều kiện đã chọn, không rẽ nhánh theo loại gói thuê (ADR-0004).
 * BR-119 kiểm hai thời điểm thực tế, không kiểm khoảng đệm sau khi trả xe.
 *
 * @param turnaroundBuffer đệm theo BR-109/BR-116
 * @param minimumDuration tối thiểu theo BR-113 hoặc rỗng
 * @param branchOpensAt giờ mở cửa, gồm biên
 * @param branchClosesAt giờ đóng cửa, gồm biên
 * @param minimumAdvance báo trước tối thiểu theo BR-121
 * @param maximumAdvance cửa sổ đặt theo tháng lịch, áp lên thời điểm nhận xe
 */
public record ResolvedRentalTermsPolicy(
        Duration turnaroundBuffer,
        Optional<Duration> minimumDuration,
        LocalTime branchOpensAt,
        LocalTime branchClosesAt,
        Duration minimumAdvance,
        Period maximumAdvance
) implements RentalTermsPolicy {
    /** Bảo vệ các tham số nội bộ của policy, không dùng null để biểu diễn thiếu quy tắc. */
    public ResolvedRentalTermsPolicy {
        Objects.requireNonNull(turnaroundBuffer, "turnaroundBuffer");
        Objects.requireNonNull(minimumDuration, "minimumDuration");
        Objects.requireNonNull(branchOpensAt, "branchOpensAt");
        Objects.requireNonNull(branchClosesAt, "branchClosesAt");
        Objects.requireNonNull(minimumAdvance, "minimumAdvance");
        Objects.requireNonNull(maximumAdvance, "maximumAdvance");
        if (turnaroundBuffer.isNegative() || minimumAdvance.isNegative()
                || minimumDuration.filter(value -> value.isNegative() || value.isZero()).isPresent()
                || maximumAdvance.isNegative() || maximumAdvance.isZero()
                || branchOpensAt.isAfter(branchClosesAt)) {
            throw new IllegalArgumentException("Invalid rental terms policy parameters.");
        }
    }

    /** Kiểm thứ tự khoảng, tối thiểu, giờ chi nhánh và cửa sổ đặt; không thay đổi đầu vào. */
    @Override
    public void validate(Instant startInclusive, Instant endExclusive, Instant at, ZoneId zone) {
        Validations.required(startInclusive, "startInclusive");
        Validations.required(endExclusive, "endExclusive");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(zone, "zone");
        if (!endExclusive.isAfter(startInclusive)) {
            throw DomainException.invalidInput("endExclusive must be after startInclusive.");
        }
        Duration duration = Duration.between(startInclusive, endExclusive);
        if (minimumDuration.filter(minimum -> duration.compareTo(minimum) < 0).isPresent()) {
            throw DomainException.ruleViolation(ErrorCode.RENTAL_DURATION_TOO_SHORT);
        }
        try {
            validateBranchTime(startInclusive.atZone(zone).toLocalTime());
            validateBranchTime(endExclusive.atZone(zone).toLocalTime());
            Instant earliest = at.plus(minimumAdvance);
            Instant latest = at.atZone(zone).plus(maximumAdvance).toInstant();
            if (startInclusive.isBefore(earliest) || startInclusive.isAfter(latest)) {
                throw DomainException.ruleViolation(ErrorCode.BOOKING_WINDOW_VIOLATION);
            }
        } catch (DateTimeException | ArithmeticException failure) {
            throw DomainException.invalidInput("The rental period is outside the supported date range.");
        }
    }

    /** Chỉ hai biên 06:00 và 23:00 được bao gồm; không làm tròn giây hoặc nano giây. */
    private void validateBranchTime(LocalTime time) {
        if (time.isBefore(branchOpensAt) || time.isAfter(branchClosesAt)) {
            throw DomainException.ruleViolation(ErrorCode.OUTSIDE_BRANCH_HOURS);
        }
    }
}
