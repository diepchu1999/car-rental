package com.carrental.booking.application.service;

import com.carrental.booking.domain.Booking;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.pricing.api.QuoteLineRef;
import com.carrental.pricing.api.QuoteRef;
import com.carrental.rental.api.RentalTerms;
import com.carrental.shared.rental.*;
import java.util.List;
import static com.carrental.booking.domain.BookingTestFixtures.*;

/** Fixture của use case, tách dữ liệu báo giá khỏi các mock điều phối. */
final class BookingCreationFixtures {
    /** Tiện ích không có instance. */
    private BookingCreationFixtures() { }

    /** Báo giá một ngày đã chốt; chủ và xe thay theo từng cuộc đua. */
    static QuoteRef quote(String suffix, String owner, String vehicle) {
        var value = price();
        var line = line();
        return new QuoteRef("BG-2611-" + suffix, owner, vehicle, START, END,
                RentalType.DAILY, DriveMode.SELF_DRIVE, PickupMethod.BRANCH, value.dailyRate(),
                List.of(new QuoteLineRef(line.type(), line.dayType(), line.startAt(), line.hours(),
                        line.dailyRate(), line.uplift(), line.amount())), value.insuranceCalculation(),
                value.insuranceValue(), value.insuranceBillingDays(), value.amounts().rentalAmount(),
                value.amounts().insuranceFee(), value.amounts().totalAmount(), CREATED, CREATED.plusSeconds(900));
    }

    /** Chuyển điều kiện fixture thành hợp đồng công khai của rental. */
    static RentalTerms rentalTerms() {
        var value = terms();
        return new RentalTerms(value.turnaroundBuffer(), value.minimumDuration(), value.branchOpensAt(),
                value.branchClosesAt(), value.minimumAdvance(), value.maximumAdvance());
    }

    /** Mô phỏng đơn đã huỷ để chứng minh phát lại không đổi kết quả tạo ban đầu. */
    static Booking cancelled(Booking value) {
        return new Booking(value.code(), value.customerAccountCode(), value.quoteCode(), value.vehicleCode(),
                value.branchCode(), value.reservationCode(), value.startAt(), value.endAt(), value.rentalType(),
                value.driveMode(), value.pickupMethod(), value.price(), value.rentalTerms(), value.holdExpiresAt(),
                BookingStatus.CANCELLED, value.createdAt(), value.createdAt().plusSeconds(60));
    }
}
