package com.carrental.booking.api;

import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.Objects;
import java.util.Optional;

/**
 * Điều kiện đã được phân giải và kiểm cho khoảng thuê theo BR-125.
 * Không chứa loại sở hữu, không phải một chỗ giữ hay cam kết xe còn trống.
 *
 * @param turnaroundBuffer đệm chưa cộng vào khoảng thuê (BR-109, BR-116)
 * @param minimumDuration tối thiểu riêng của gói, rỗng nếu chưa có quy tắc (BR-113)
 * @param branchOpensAt giờ nhận/trả sớm nhất, gồm biên (BR-119)
 * @param branchClosesAt giờ nhận/trả muộn nhất, gồm biên (BR-119)
 * @param minimumAdvance khoảng báo trước tối thiểu (BR-121)
 * @param maximumAdvance giới hạn theo tháng lịch, không phải số ngày cố định (BR-121)
 */
public record RentalTerms(
        Duration turnaroundBuffer,
        Optional<Duration> minimumDuration,
        LocalTime branchOpensAt,
        LocalTime branchClosesAt,
        Duration minimumAdvance,
        Period maximumAdvance
) {
    /** Ngăn hợp đồng cross-module chứa trường null do lỗi ánh xạ nội bộ. */
    public RentalTerms {
        Objects.requireNonNull(turnaroundBuffer, "turnaroundBuffer");
        Objects.requireNonNull(minimumDuration, "minimumDuration");
        Objects.requireNonNull(branchOpensAt, "branchOpensAt");
        Objects.requireNonNull(branchClosesAt, "branchClosesAt");
        Objects.requireNonNull(minimumAdvance, "minimumAdvance");
        Objects.requireNonNull(maximumAdvance, "maximumAdvance");
    }
}
