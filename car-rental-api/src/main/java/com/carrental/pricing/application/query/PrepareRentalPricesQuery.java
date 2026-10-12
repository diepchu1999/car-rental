package com.carrental.pricing.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import java.time.Instant;

/** Khoảng dùng tính theo lô BR-126; không chứa mã xe hoặc tham số do client tự đặt. */
public record PrepareRentalPricesQuery(RentalType rentalType, Instant startAt, Instant endAt) {
    /** Kiểm hình dạng, còn điều kiện đặt thuộc booking và lịch lễ thuộc policy pricing. */
    public PrepareRentalPricesQuery {
        rentalType = Validations.required(rentalType, "rentalType");
        startAt = Validations.required(startAt, "startAt");
        endAt = Validations.required(endAt, "endAt");
        if (!endAt.isAfter(startAt)) throw DomainException.invalidInput("endAt must be after startAt.");
    }
}
