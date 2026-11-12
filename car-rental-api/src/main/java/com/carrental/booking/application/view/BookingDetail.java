package com.carrental.booking.application.view;

import com.carrental.booking.domain.*;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.time.Instant;

/** Mô hình đọc đơn gồm các giá trị đã đóng băng, không phải row JDBC và không chứa ID số. */
public record BookingDetail(String code, String customerAccountCode, String quoteCode, String vehicleCode,
        String branchCode, String reservationCode, Instant startAt, Instant endAt, RentalType rentalType,
        DriveMode driveMode, PickupMethod pickupMethod, BookingPriceSnapshot price, BookingRentalTerms rentalTerms,
        Instant holdExpiresAt, BookingStatus status, Instant createdAt, Instant statusChangedAt) {
    /** Đọc trạng thái hiện tại khi xem đơn, không tính lại giá/điều kiện. */
    public static BookingDetail fromBooking(Booking booking) {
        return new BookingDetail(booking.code(), booking.customerAccountCode(), booking.quoteCode(), booking.vehicleCode(),
                booking.branchCode(), booking.reservationCode(), booking.startAt(), booking.endAt(), booking.rentalType(),
                booking.driveMode(), booking.pickupMethod(), booking.price(), booking.rentalTerms(), booking.holdExpiresAt(),
                booking.status(), booking.createdAt(), booking.statusChangedAt());
    }

    /** Phát lại kết quả tạo ban đầu dù trạng thái đơn đã đổi; không sửa trạng thái đang lưu (api-guideline §5). */
    public static BookingDetail fromCreatedSnapshot(Booking booking) {
        return new BookingDetail(booking.code(), booking.customerAccountCode(), booking.quoteCode(), booking.vehicleCode(),
                booking.branchCode(), booking.reservationCode(), booking.startAt(), booking.endAt(), booking.rentalType(),
                booking.driveMode(), booking.pickupMethod(), booking.price(), booking.rentalTerms(), booking.holdExpiresAt(),
                BookingStatus.PENDING_PAYMENT, booking.createdAt(), booking.createdAt());
    }
}
