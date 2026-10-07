package com.carrental.search.application.service;

import com.carrental.search.domain.SearchSort;
import com.carrental.search.domain.policy.SearchSupportPolicy;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Cổng hỗ trợ lát cắt 1 của BR-125/126; chỉ resolver rẽ nhánh RentalType theo ADR-0004/R6. */
@Component
class SearchSupportPolicyResolver {
    /** Phân giải một lần, không mặc định từ lựa chọn chưa hỗ trợ sang tự lái hoặc gói ngày. */
    SearchSupportPolicy resolve(RentalType rentalType, DriveMode driveMode,
            PickupMethod pickupMethod, SearchSort sort) {
        Objects.requireNonNull(rentalType, "rentalType");
        Objects.requireNonNull(driveMode, "driveMode");
        Objects.requireNonNull(pickupMethod, "pickupMethod");
        Objects.requireNonNull(sort, "sort");
        if (rentalType == RentalType.MONTHLY) {
            return unsupported(ErrorCode.SEARCH_RENTAL_TYPE_NOT_SUPPORTED);
        }
        if (driveMode == DriveMode.WITH_DRIVER) {
            return unsupported(ErrorCode.SEARCH_DRIVE_MODE_NOT_SUPPORTED);
        }
        if (pickupMethod == PickupMethod.DELIVERY) {
            return unsupported(ErrorCode.SEARCH_PICKUP_METHOD_NOT_SUPPORTED);
        }
        if (sort != SearchSort.NEAREST) {
            return unsupported(ErrorCode.SEARCH_SORT_NOT_SUPPORTED);
        }
        return () -> { /* Các lựa chọn đều thuộc lát cắt đã hỗ trợ. */ };
    }

    /** Trả policy từ chối bằng mã ổn định, độc lập với giao thức HTTP. */
    private SearchSupportPolicy unsupported(ErrorCode code) {
        return () -> { throw DomainException.ruleViolation(code); };
    }
}
