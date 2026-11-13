package com.carrental.booking.adapter.in.rest.client.response;

import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Hợp đồng đơn cho khách: mã nghiệp vụ, khoản tiền và trạng thái; không lộ ID số hoặc cấu hình nội bộ. */
public record BookingResponse(String code, String quoteCode, String vehicleCode, String branchCode,
        Instant startAt, Instant endAt, RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod,
        List<BookingLineResponse> lines, long rentalAmount, long insuranceFee, long totalAmount,
        BigDecimal depositRatio, long deposit, long balance, BookingStatus status,
        Instant holdExpiresAt, Instant createdAt, Instant statusChangedAt) {
    /** Không cho bên giữ danh sách nguồn thay đổi response đã tạo. */
    public BookingResponse { lines = List.copyOf(lines); }

    /** BR-122/201: map snapshot và cọc/phần còn lại đã chốt; không trả khoản thế chấp giả. */
    public static BookingResponse fromDomain(BookingDetail detail) {
        var amounts = detail.price().amounts();
        return new BookingResponse(detail.code(), detail.quoteCode(), detail.vehicleCode(), detail.branchCode(),
                detail.startAt(), detail.endAt(), detail.rentalType(), detail.driveMode(), detail.pickupMethod(),
                detail.price().lines().stream().map(BookingLineResponse::fromDomain).toList(),
                amounts.rentalAmount(), amounts.insuranceFee(), amounts.totalAmount(), amounts.depositRatio(),
                amounts.deposit(), amounts.balance(), detail.status(), detail.holdExpiresAt(),
                detail.createdAt(), detail.statusChangedAt());
    }
}
