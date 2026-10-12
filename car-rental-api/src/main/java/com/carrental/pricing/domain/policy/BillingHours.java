package com.carrental.pricing.domain.policy;

import com.carrental.shared.error.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Tính giờ thực tế, làm tròn lên cả phần nhỏ hơn một giây theo BR-237. */
final class BillingHours {
    private static final long SECONDS_PER_HOUR = 3_600;

    /** Tiện ích nội bộ của các policy, không có instance. */
    private BillingHours() { }

    /** Khoảng không dương là đầu vào sai; không dùng ngày lịch thay cho thời lượng thực tế. */
    static int between(Instant startAt, Instant endAt) {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) throw DomainException.invalidInput("endAt must be after startAt.");
        var duration = Duration.between(startAt, endAt);
        long whole = duration.getSeconds() / SECONDS_PER_HOUR;
        if (duration.getSeconds() % SECONDS_PER_HOUR != 0 || duration.getNano() != 0) whole++;
        return Math.toIntExact(whole);
    }
}
