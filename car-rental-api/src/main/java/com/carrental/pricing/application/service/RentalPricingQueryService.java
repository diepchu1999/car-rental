package com.carrental.pricing.application.service;

import com.carrental.pricing.application.port.in.PrepareRentalPricesUseCase;
import com.carrental.pricing.application.port.out.ReadPricingPolicyPort;
import com.carrental.pricing.application.query.PrepareRentalPricesQuery;
import com.carrental.pricing.application.view.PreparedRentalPrices;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/** Chuẩn bị giá BR-126/218 bằng cùng policy và PricingSchedule của báo giá, không truy vấn xe. */
@Service
class RentalPricingQueryService implements PrepareRentalPricesUseCase {
    private final RentalPricingPolicyResolver resolver;
    private final ReadPricingPolicyPort parameters;
    private final Clock clock;

    /** Nhận nguồn cấu hình theo ADR-0013 và đồng hồ ứng dụng; không phụ thuộc vehicle/availability. */
    RentalPricingQueryService(RentalPricingPolicyResolver resolver, ReadPricingPolicyPort parameters, Clock clock) {
        this.resolver = resolver;
        this.parameters = parameters;
        this.clock = clock;
    }

    /** Đọc cấu hình và phân loại lịch đúng một lần, kể cả khi lô giá sau đó rỗng. */
    @Override
    @Transactional(readOnly = true)
    public PreparedRentalPrices prepare(PrepareRentalPricesQuery query) {
        Validations.required(query, "query");
        var policy = resolver.resolve(query.rentalType());
        var settings = parameters.parametersAt(clock.instant());
        var schedule = policy.prepare(query.startAt(), query.endAt(), settings, clock.getZone());
        return dailyRate -> schedule.calculateRent(dailyRate).rentalAmount();
    }
}
