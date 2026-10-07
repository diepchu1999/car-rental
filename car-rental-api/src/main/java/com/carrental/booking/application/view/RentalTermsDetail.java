package com.carrental.booking.application.view;

import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.Optional;

/**
 * Read model nội bộ của điều kiện thuê đã kiểm, không đưa qua ranh giới module.
 * Adapter ánh xạ sang hợp đồng booking.api.RentalTerms theo ADR-0004/0008.
 *
 * @param turnaroundBuffer đệm chưa cộng theo BR-109/BR-116
 * @param minimumDuration tối thiểu theo BR-113 hoặc rỗng
 * @param branchOpensAt giờ nhận/trả sớm nhất, gồm biên BR-119
 * @param branchClosesAt giờ nhận/trả muộn nhất, gồm biên BR-119
 * @param minimumAdvance báo trước tối thiểu BR-121
 * @param maximumAdvance giới hạn theo tháng lịch BR-121
 */
public record RentalTermsDetail(
        Duration turnaroundBuffer,
        Optional<Duration> minimumDuration,
        LocalTime branchOpensAt,
        LocalTime branchClosesAt,
        Duration minimumAdvance,
        Period maximumAdvance
) {
}
