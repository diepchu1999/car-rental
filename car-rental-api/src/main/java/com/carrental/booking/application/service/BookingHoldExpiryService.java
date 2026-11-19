package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.booking.application.command.ExpireBookingHoldCommand;
import com.carrental.booking.application.port.in.ExpireBookingHoldUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** BR-103/304: thứ tự ghi đơn rồi lịch xe giống khách huỷ, tránh đảo thứ tự khoá giữa hai luồng. */
@Service
class BookingHoldExpiryService implements ExpireBookingHoldUseCase {
    private final ReadBookingPort reads;
    private final WriteBookingPort writes;
    private final AvailabilityDirectory availability;

    /** Chỉ gọi availability.api; không truy cập CallerDirectory vì đây không phải HTTP. */
    BookingHoldExpiryService(ReadBookingPort reads, WriteBookingPort writes, AvailabilityDirectory availability) {
        this.reads = reads;
        this.writes = writes;
        this.availability = availability;
    }

    /**
     * Bean riêng để lời gọi qua proxy thực sự mở REQUIRES_NEW. Chỉ bắt lỗi ở bên ngoài proxy này.
     * CAS đơn thắng mới nhả giữ chỗ; lỗi ghi lịch sử/nhả chỗ làm rollback cả dòng.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(ExpireBookingHoldCommand command) {
        Validations.required(command, "command");
        var found = reads.loadForExpiry(command.bookingCode());
        if (found.isEmpty()) {
            // Giữ khả năng dọn hold độc lập đã tồn tại trước module booking, qua đúng API availability.
            return availability.expireHold(command.reservationCode(), command.expiredAt()) == HoldExpiryResult.RELEASED;
        }
        var original = found.orElseThrow();
        if (!original.reservationCode().equals(command.reservationCode())) {
            throw new IllegalStateException("Booking and expiry candidate reference different reservations.");
        }
        if (original.status() != BookingStatus.PENDING_PAYMENT) return false;
        var cancelled = original.expire(command.expiredAt());
        if (cancelled.isEmpty() || !writes.cancelExpiredPending(cancelled.orElseThrow())) return false;
        var result = availability.expireHold(original.reservationCode(), command.expiredAt());
        if (result == HoldExpiryResult.NOT_ELIGIBLE) {
            throw new IllegalStateException("Expired booking reservation cannot be released.");
        }
        // ALREADY_RELEASED còn giúp khép đơn local bị job bulk cũ nhả chỗ trước khi nối booking.
        return true;
    }
}
