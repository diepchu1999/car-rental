package com.carrental.booking.application.service;

import com.carrental.booking.domain.policy.RentalTermsPolicy;
import com.carrental.booking.domain.policy.ResolvedRentalTermsPolicy;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.Optional;

/** Điểm duy nhất chọn điều kiện theo RentalType của booking, tuân ADR-0004 và R6. */
@Component
class RentalTermsPolicyResolver {
    /** Phân giải BR-109/113/116/119/121; không coi gói ngày là bắt buộc thuê đủ 24 giờ. */
    RentalTermsPolicy resolve(RentalType rentalType, PickupMethod pickupMethod) {
        Validations.required(rentalType, "rentalType");
        Validations.required(pickupMethod, "pickupMethod");
        Duration advance = switch (pickupMethod) {
            case BRANCH -> Duration.ofHours(1);
            case DELIVERY -> Duration.ofHours(3);
        };
        return switch (rentalType) {
            case HOURLY -> new ResolvedRentalTermsPolicy(
                    Duration.ofHours(1), Optional.of(Duration.ofHours(4)),
                    LocalTime.of(6, 0), LocalTime.of(23, 0), advance, Period.ofMonths(6));
            case DAILY, MONTHLY -> new ResolvedRentalTermsPolicy(
                    Duration.ofHours(2), Optional.empty(),
                    LocalTime.of(6, 0), LocalTime.of(23, 0), advance, Period.ofMonths(6));
        };
    }
}
