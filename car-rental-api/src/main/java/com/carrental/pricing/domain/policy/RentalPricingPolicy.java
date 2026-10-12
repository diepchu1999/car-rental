package com.carrental.pricing.domain.policy;

import com.carrental.pricing.domain.PricingParameters;
import com.carrental.pricing.domain.PricingSchedule;
import java.time.Instant;
import java.time.ZoneId;

/** Policy gói đã phân giải theo ADR-0004; domain không tự hỏi RentalType. */
public interface RentalPricingPolicy {
    /** Chuẩn bị các mốc/loại ngày một lần, không truy vấn CSDL hoặc đọc cấu hình. */
    PricingSchedule prepare(Instant startAt, Instant endAt, PricingParameters parameters, ZoneId zone);
}
