package com.carrental.booking.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import java.time.Instant;

/**
 * Đầu vào đọc điều kiện cho một khoảng thuê thực tế theo BR-125.
 * @param rentalType gói thuê
 * @param pickupMethod cách nhận xe
 * @param startInclusive giờ nhận chưa cộng đệm
 * @param endExclusive giờ trả chưa cộng đệm
 */
public record GetRentalTermsQuery(
        RentalType rentalType, PickupMethod pickupMethod,
        Instant startInclusive, Instant endExclusive
) {
    /** Kiểm đủ đầu vào và khoảng hữu hạn có độ dài dương, kể cả gọi constructor trực tiếp. */
    public GetRentalTermsQuery {
        rentalType = Validations.required(rentalType, "rentalType");
        pickupMethod = Validations.required(pickupMethod, "pickupMethod");
        startInclusive = Validations.required(startInclusive, "startInclusive");
        endExclusive = Validations.required(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw DomainException.invalidInput("endExclusive must be after startInclusive.");
        }
    }

    /** Tạo query từ giá trị thô, không phụ thuộc request DTO hoặc module khác. */
    public static GetRentalTermsQuery from(
            RentalType rentalType, PickupMethod pickupMethod,
            Instant startInclusive, Instant endExclusive
    ) {
        return new GetRentalTermsQuery(rentalType, pickupMethod, startInclusive, endExclusive);
    }
}
