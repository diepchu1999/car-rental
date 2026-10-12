package com.carrental.pricing.application.service;

import com.carrental.pricing.domain.policy.DailyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.HourlyRentalPricingPolicy;
import com.carrental.pricing.domain.policy.RentalPricingPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Điểm duy nhất chọn cách tính theo RentalType trong pricing, ADR-0004/R6. */
@Component
class RentalPricingPolicyResolver {
    private final RentalPricingPolicy daily = new DailyRentalPricingPolicy();
    private final RentalPricingPolicy hourly = new HourlyRentalPricingPolicy();

    /** BR-114/235: truyền policy đã chọn vào domain; MONTHLY chưa thuộc lát cắt này, BR-125. */
    RentalPricingPolicy resolve(RentalType rentalType) {
        return switch (Objects.requireNonNull(rentalType, "rentalType")) {
            case DAILY -> daily;
            case HOURLY -> hourly;
            case MONTHLY -> throw DomainException.ruleViolation(ErrorCode.QUOTE_RENTAL_TYPE_NOT_SUPPORTED);
        };
    }

    /** BR-125: thứ tự lỗi tất định gói tháng, có tài xế, giao xe; không bỏ qua lựa chọn chưa hỗ trợ. */
    RentalPricingPolicy resolve(RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod) {
        var policy = resolve(rentalType);
        if (Objects.requireNonNull(driveMode, "driveMode") == DriveMode.WITH_DRIVER) {
            throw DomainException.ruleViolation(ErrorCode.QUOTE_DRIVE_MODE_NOT_SUPPORTED);
        }
        if (Objects.requireNonNull(pickupMethod, "pickupMethod") == PickupMethod.DELIVERY) {
            throw DomainException.ruleViolation(ErrorCode.QUOTE_PICKUP_METHOD_NOT_SUPPORTED);
        }
        return policy;
    }
}
