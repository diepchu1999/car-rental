package com.carrental.pricing.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import java.time.Instant;

/** Đầu vào tạo báo giá BR-122/125, không nhận giá, tổng tiền hoặc hạn do client quyết định. */
public record CreateQuoteCommand(String vehicleCode, Instant startAt, Instant endAt,
        RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod) {
    /** Kiểm cấu trúc trước mọi truy vấn; điều kiện thuê cụ thể thuộc booking.api. */
    public CreateQuoteCommand {
        vehicleCode = Validations.requiredText(vehicleCode, "vehicleCode");
        startAt = Validations.required(startAt, "startAt");
        endAt = Validations.required(endAt, "endAt");
        rentalType = Validations.required(rentalType, "rentalType");
        driveMode = Validations.required(driveMode, "driveMode");
        pickupMethod = Validations.required(pickupMethod, "pickupMethod");
        if (!endAt.isAfter(startAt)) throw DomainException.invalidInput("endAt must be after startAt.");
        // Giới hạn biểu diễn của timestamptz, không làm tròn khoảng làm thay đổi giờ tính tiền BR-237.
        if (startAt.getNano() % 1_000 != 0 || endAt.getNano() % 1_000 != 0) {
            throw DomainException.invalidInput("Rental timestamps support at most microsecond precision.");
        }
    }

    /** Tạo từ dữ liệu thô, không phụ thuộc DTO của REST adapter. */
    public static CreateQuoteCommand from(String vehicleCode, Instant startAt, Instant endAt,
            RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod) {
        return new CreateQuoteCommand(vehicleCode, startAt, endAt, rentalType, driveMode, pickupMethod);
    }
}
