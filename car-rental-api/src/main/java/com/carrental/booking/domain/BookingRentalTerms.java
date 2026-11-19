package com.carrental.booking.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.Objects;
import java.util.Optional;

/**
 * Điều kiện thuê đã áp khi tạo đơn, chỉ đọc bản sao khi xử lý đơn về sau (BR-125, ADR-0014).
 * @param turnaroundBuffer khoảng đệm chưa cộng vào giờ trả thực tế
 * @param minimumDuration tối thiểu riêng của gói; rỗng nghĩa là không có quy tắc riêng
 * @param branchOpensAt đầu giờ nhận/trả tại chi nhánh, gồm biên
 * @param branchClosesAt cuối giờ nhận/trả tại chi nhánh, gồm biên
 * @param minimumAdvance thời gian báo trước tối thiểu
 * @param maximumAdvance cửa sổ đặt theo tháng lịch
 */
public record BookingRentalTerms(Duration turnaroundBuffer, Optional<Duration> minimumDuration,
        LocalTime branchOpensAt, LocalTime branchClosesAt, Duration minimumAdvance, Period maximumAdvance) {
    /** Chỉ kiểm hình dạng bản sao; không tự chọn số giờ đệm hay đổi tháng thành số ngày. */
    public BookingRentalTerms {
        Objects.requireNonNull(turnaroundBuffer, "turnaroundBuffer");
        Objects.requireNonNull(minimumDuration, "minimumDuration");
        Objects.requireNonNull(branchOpensAt, "branchOpensAt");
        Objects.requireNonNull(branchClosesAt, "branchClosesAt");
        Objects.requireNonNull(minimumAdvance, "minimumAdvance");
        Objects.requireNonNull(maximumAdvance, "maximumAdvance");
        if (turnaroundBuffer.isNegative() || turnaroundBuffer.isZero()
                || minimumDuration.stream().anyMatch(value -> value.isNegative() || value.isZero())
                || !branchClosesAt.isAfter(branchOpensAt)
                || branchOpensAt.getNano() % 1_000 != 0 || branchClosesAt.getNano() % 1_000 != 0
                || minimumAdvance.isNegative() || minimumAdvance.isZero()
                || maximumAdvance.isNegative() || maximumAdvance.isZero() || maximumAdvance.getDays() != 0) {
            throw new IllegalArgumentException("Invalid frozen rental terms.");
        }
    }
}
