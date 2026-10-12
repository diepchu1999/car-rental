package com.carrental.pricing.application.port.out;

import com.carrental.pricing.domain.PricingParameters;
import java.time.Instant;

/** Nguồn tham số giá thay được bằng module config mà không sửa domain (ADR-0013). */
public interface ReadPricingPolicyPort {
    /** Đọc bộ tham số có hiệu lực tại mốc application cung cấp, không tự lấy thời gian hiện tại. */
    PricingParameters parametersAt(Instant at);
}
