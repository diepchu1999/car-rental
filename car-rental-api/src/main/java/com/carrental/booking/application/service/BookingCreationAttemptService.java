package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.booking.application.command.CreateBookingAttemptCommand;
import com.carrental.booking.application.port.in.CreateBookingAttemptUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.Booking;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Savepoint bao cả giữ chỗ và đơn của mỗi lần thử mã, không commit độc lập (ADR-0005/0014). */
@Service
class BookingCreationAttemptService implements CreateBookingAttemptUseCase {
    private final AvailabilityDirectory availability;
    private final WriteBookingPort writes;
    private final ReadBookingPort reads;

    /** Dùng cổng availability duy nhất; không SQL hoặc gọi adapter xuyên module. */
    BookingCreationAttemptService(AvailabilityDirectory availability, WriteBookingPort writes, ReadBookingPort reads) {
        this.availability = availability; this.writes = writes; this.reads = reads;
    }

    /** NESTED đi qua bean riêng, tránh self-invocation làm mất savepoint; sai mã rollback trước lần thử tiếp. */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public BookingDetail createAttempt(CreateBookingAttemptCommand command) {
        var quote = command.quote();
        var held = availability.hold(command.vehicle().id(), new Period(quote.startAt(), quote.endAt()),
                command.terms().turnaroundBuffer(), command.code());
        var booking = Booking.create(command.code(), quote.customerAccountCode(), quote.code(), quote.vehicleCode(),
                command.vehicle().branchCode(), held.code(), quote.startAt(), quote.endAt(), quote.rentalType(),
                quote.driveMode(), quote.pickupMethod(), command.price(), command.terms(), held.holdExpiresAt(), command.createdAt());
        if (!writes.insert(booking)) throw new BookingCodeCollisionException();
        var stored = reads.loadOwned(booking.code(), booking.customerAccountCode())
                .orElseThrow(() -> new IllegalStateException("Inserted booking could not be reloaded."));
        return BookingDetail.fromBooking(stored);
    }
}
