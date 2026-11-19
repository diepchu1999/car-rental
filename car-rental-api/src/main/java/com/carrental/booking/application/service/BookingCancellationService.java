package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.port.in.CancelBookingUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BR-301/304: điều phối huỷ đơn chờ, lịch sử và nhả giữ chỗ; không viết bảng availability. */
@Service
class BookingCancellationService implements CancelBookingUseCase {
    private final CallerDirectory callers;
    private final ReadBookingPort reads;
    private final WriteBookingPort writes;
    private final AvailabilityDirectory availability;
    private final Clock clock;

    /** Nhận các cổng module và đồng hồ; không kiểm lại giấy tờ hoặc định giá khi huỷ. */
    BookingCancellationService(CallerDirectory callers, ReadBookingPort reads, WriteBookingPort writes,
            AvailabilityDirectory availability, Clock clock) {
        this.callers = callers;
        this.reads = reads;
        this.writes = writes;
        this.availability = availability;
        this.clock = clock;
    }

    /**
     * Kiểm chủ trong SQL; CAS trạng thái trước khi nhả chỗ để hai yêu cầu không cùng thắng.
     * Lỗi ở bất kỳ bước ghi nào phải rollback cả đơn, lịch sử và giữ chỗ (BR-304).
     */
    @Override
    @Transactional
    public BookingDetail cancel(CancelBookingCommand command) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Validations.required(command, "command");
        var original = reads.loadOwned(command.code(), caller.accountCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.BOOKING_NOT_FOUND));
        var cancelled = original.cancel(clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (!writes.cancelPendingByCustomer(cancelled)) {
            throw DomainException.ruleViolation(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION);
        }
        availability.release(cancelled.reservationCode());
        return BookingDetail.fromBooking(cancelled);
    }
}
