package com.carrental.vehicle.application.service;

import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.policy.CollateralPolicy;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Điểm duy nhất phân nhánh theo sở hữu và gói thuê cho thế chấp, theo BR-209 và ADR-0004. */
@Component
class CollateralPolicyResolver {
    /**
     * Xe công ty thuê giờ/ngày được miễn; thuê tháng cần thế chấp.
     * PARTNER chưa có chính sách GĐ2: báo lỗi nội bộ rõ, không đoán cờ hoặc âm thầm loại xe.
     */
    CollateralPolicy resolve(OwnershipType ownershipType, RentalType rentalType) {
        Objects.requireNonNull(ownershipType, "ownershipType must not be null.");
        Objects.requireNonNull(rentalType, "rentalType must not be null.");
        return switch (ownershipType) {
            case COMPANY -> switch (rentalType) {
                case HOURLY, DAILY -> () -> true;
                case MONTHLY -> () -> false;
            };
            case PARTNER -> throw new IllegalStateException(
                    "Collateral policy for PARTNER vehicles is not implemented.");
        };
    }
}
