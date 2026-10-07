package com.carrental.booking.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Optional;

/** Policy thuần đã phân giải theo ADR-0004; domain không cần biết RentalType. */
public interface RentalTermsPolicy {
    /** Trả khoảng đệm theo BR-109/BR-116, chưa cộng vào lịch. */
    Duration turnaroundBuffer();

    /** Trả thời lượng tối thiểu nếu BR có quy định; không suy ra tối thiểu cho gói ngày/tháng. */
    Optional<Duration> minimumDuration();

    /** Trả giờ bắt đầu nhận/trả xe theo BR-119, gồm biên. */
    LocalTime branchOpensAt();

    /** Trả giờ kết thúc nhận/trả xe theo BR-119, gồm biên. */
    LocalTime branchClosesAt();

    /** Trả khoảng báo trước tối thiểu theo BR-121. */
    Duration minimumAdvance();

    /** Trả giới hạn đặt trước theo tháng lịch của BR-121. */
    Period maximumAdvance();

    /**
     * Kiểm khoảng thực tế, không cộng đệm và không truy vấn xe.
     * @param startInclusive giờ nhận xe
     * @param endExclusive giờ trả xe
     * @param at mốc hiện tại được use case chụp đúng một lần
     * @param zone múi giờ của đồng hồ ứng dụng
     */
    void validate(Instant startInclusive, Instant endExclusive, Instant at, ZoneId zone);
}
