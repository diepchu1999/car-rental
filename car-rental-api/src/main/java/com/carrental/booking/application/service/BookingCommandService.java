package com.carrental.booking.application.service;

import com.carrental.booking.application.command.CreateBookingCommand;
import com.carrental.booking.application.command.CreateBookingAttemptCommand;
import com.carrental.booking.application.port.in.CreateBookingUseCase;
import com.carrental.booking.application.port.in.CreateBookingAttemptUseCase;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.*;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.RentalEligibilityDirectory;
import com.carrental.pricing.api.QuoteDirectory;
import com.carrental.rental.api.RentalTermsDirectory;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.api.VehicleDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Điều phối một giao dịch tạo đơn, không tự kiểm xe trống hoặc ghi SQL (BR-102/122/131). */
@Service
class BookingCommandService implements CreateBookingUseCase {
    private static final int MAX_CODE_ATTEMPTS = 5; // Giới hạn kỹ thuật như chi nhánh, xe và báo giá.
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyMM", Locale.ROOT);
    private final CallerDirectory callers;
    private final QuoteDirectory quotes;
    private final VehicleDirectory vehicles;
    private final RentalEligibilityDirectory eligibility;
    private final RentalTermsDirectory rental;
    private final BookingDepositPolicyResolver deposits;
    private final WriteBookingIdempotencyPort keys;
    private final ReadBookingPort reads;
    private final CreateBookingAttemptUseCase attempts;
    private final BusinessCodeGenerator codes;
    private final Clock clock;

    /** Chỉ phụ thuộc cổng và policy nội bộ; không lấy chủ/TTL/giá từ request. */
    BookingCommandService(CallerDirectory callers, QuoteDirectory quotes, VehicleDirectory vehicles,
            RentalEligibilityDirectory eligibility, RentalTermsDirectory rental, BookingDepositPolicyResolver deposits,
            WriteBookingIdempotencyPort keys, ReadBookingPort reads, CreateBookingAttemptUseCase attempts,
            BusinessCodeGenerator codes, Clock clock) {
        this.callers = callers; this.quotes = quotes; this.vehicles = vehicles; this.eligibility = eligibility;
        this.rental = rental; this.deposits = deposits; this.keys = keys; this.reads = reads;
        this.attempts = attempts; this.codes = codes; this.clock = clock;
    }

    /** Key phát lại đi trước kiểm hạn báo giá; request mới kiểm đủ điều kiện trước khi giữ chỗ. */
    @Override
    @Transactional
    public BookingDetail create(CreateBookingCommand command) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Validations.required(command, "command");
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var proposed = BookingIdempotency.create(caller.accountCode(), command.idempotencyKey(), command.quoteCode(), nextCode(at), at);
        var claim = keys.claim(proposed);
        if (!claim.newlyClaimed()) {
            if (!claim.record().quoteCode().equals(command.quoteCode())) {
                throw DomainException.conflict(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            var saved = reads.loadOwned(claim.record().bookingCode(), caller.accountCode())
                    .orElseThrow(() -> new IllegalStateException("Idempotency result could not be reloaded."));
            return BookingDetail.fromCreatedSnapshot(saved);
        }
        // Claim có thể đã chờ transaction khác: kiểm hạn bằng mốc hiện tại sau khi chiếm, không dùng at cũ.
        var quote = quotes.getForBooking(command.quoteCode(), caller.accountCode(), clock.instant());
        if (!quote.customerAccountCode().equals(caller.accountCode()) || !quote.code().equals(command.quoteCode())) {
            throw new IllegalStateException("Quote directory returned an inconsistent owner or code.");
        }
        var ratio = deposits.resolve(quote.rentalType(), quote.driveMode(), quote.pickupMethod());
        var vehicle = vehicles.findActiveByCode(quote.vehicleCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.VEHICLE_NOT_FOUND));
        if (!vehicle.code().equals(quote.vehicleCode())) throw new IllegalStateException("Vehicle directory returned an inconsistent code.");
        eligibility.requireSelfDriveEligible(caller.accountCode(), vehicle.seats(), vehicle.transmission(), quote.endAt());
        var terms = rental.getTerms(quote.rentalType(), quote.pickupMethod(), quote.startAt(), quote.endAt());
        var frozenTerms = new BookingRentalTerms(terms.turnaroundBuffer(), terms.minimumDuration(), terms.branchOpensAt(),
                terms.branchClosesAt(), terms.minimumAdvance(), terms.maximumAdvance());
        var amounts = BookingAmounts.calculate(quote.rentalAmount(), quote.insuranceFee(), ratio);
        if (amounts.totalAmount() != quote.totalAmount()) throw new IllegalStateException("Quote total is inconsistent.");
        var price = new BookingPriceSnapshot(quote.dailyRate(), quote.lines().stream().map(line -> new BookingPriceLine(
                line.type(), line.dayType(), line.startAt(), line.hours(), line.dailyRate(), line.uplift(), line.amount())).toList(),
                quote.insuranceCalculation(), quote.insuranceValue(), quote.insuranceBillingDays(), amounts);
        var current = claim.record();
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return attempts.createAttempt(new CreateBookingAttemptCommand(current.bookingCode(), quote, vehicle, price, frozenTerms, at));
            } catch (BookingCodeCollisionException collision) {
                if (attempt + 1 == MAX_CODE_ATTEMPTS) throw new IllegalStateException("Booking code generation exhausted its retry limit.", collision);
                var replacement = nextCode(at);
                keys.replaceClaimedBookingCode(current, replacement);
                current = new BookingIdempotency(current.customerAccountCode(), current.key(), current.quoteCode(), replacement,
                        current.createdAt(), current.expiresAt());
            } catch (PendingBookingExistsException failure) {
                throw DomainException.conflict(ErrorCode.BOOKING_PENDING_PAYMENT_EXISTS);
            }
        }
        throw new IllegalStateException("Booking creation did not produce a result.");
    }

    /** Mã DT-yymm-6 dùng múi giờ ứng dụng, không dùng tháng UTC hoặc ID số (database-guideline §2). */
    private String nextCode(Instant at) {
        return "DT-" + MONTH.format(at.atZone(clock.getZone())) + "-" + codes.generate("DT").substring(3);
    }
}
