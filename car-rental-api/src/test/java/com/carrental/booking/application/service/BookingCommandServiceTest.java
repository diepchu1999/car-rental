package com.carrental.booking.application.service;

import com.carrental.booking.application.command.*;
import com.carrental.booking.application.port.in.CreateBookingAttemptUseCase;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.*;
import com.carrental.booking.domain.*;
import com.carrental.identity.api.*;
import com.carrental.pricing.api.QuoteDirectory;
import com.carrental.rental.api.RentalTermsDirectory;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.*;
import com.carrental.vehicle.api.*;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static com.carrental.booking.application.service.BookingCreationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm thứ tự cổng và đóng băng; giao dịch và tranh chấp được chứng minh riêng bằng PostgreSQL thật. */
class BookingCommandServiceTest {
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final QuoteDirectory quotes = mock(QuoteDirectory.class);
    private final VehicleDirectory vehicles = mock(VehicleDirectory.class);
    private final RentalEligibilityDirectory eligibility = mock(RentalEligibilityDirectory.class);
    private final RentalTermsDirectory rental = mock(RentalTermsDirectory.class);
    private final WriteBookingIdempotencyPort keys = mock(WriteBookingIdempotencyPort.class);
    private final ReadBookingPort reads = mock(ReadBookingPort.class);
    private final CreateBookingAttemptUseCase attempts = mock(CreateBookingAttemptUseCase.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final Clock clock = mock(Clock.class);
    private final CreateBookingCommand command = new CreateBookingCommand("BG-2611-TEST01", "key-one");
    private BookingCommandService service;

    /** Dựng hợp đồng hợp lệ, không mặc định bỏ qua những cổng mà use case cần gọi. */
    @BeforeEach
    void setUp() {
        service = new BookingCommandService(callers, quotes, vehicles, eligibility, rental,
                new BookingDepositPolicyResolver(), keys, reads, attempts, codes, clock);
        when(clock.instant()).thenReturn(CREATED);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("CUSTOMER"), null));
        when(codes.generate("DT")).thenReturn("DT-TEST01");
        doAnswer(call -> new BookingIdempotencyClaim(true, call.getArgument(0))).when(keys).claim(any());
        when(quotes.getForBooking(command.quoteCode(), "TK-OWNER1", CREATED))
                .thenReturn(quote("TEST01", "TK-OWNER1", "XE-TEST01"));
        when(vehicles.findActiveByCode("XE-TEST01")).thenReturn(Optional.of(new VehicleRef(42, "XE-TEST01", "CN-TEST01", 7, "AUTOMATIC")));
        when(rental.getTerms(any(), any(), eq(START), eq(END))).thenReturn(rentalTerms());
        when(attempts.createAttempt(any())).thenReturn(BookingDetail.fromBooking(booking()));
    }

    /** Cổng giấy tờ nhận cuối chuyến thực, không cộng đệm; tiền và điều kiện chỉ sao chép một lần. */
    @Test
    void validatesThenFreezesQuoteBeforeHolding() {
        var result = service.create(command);
        var order = inOrder(keys, quotes, vehicles, eligibility, rental, attempts);
        order.verify(keys).claim(any());
        order.verify(quotes).getForBooking(command.quoteCode(), "TK-OWNER1", CREATED);
        order.verify(vehicles).findActiveByCode("XE-TEST01");
        order.verify(eligibility).requireSelfDriveEligible("TK-OWNER1", 7, "AUTOMATIC", END);
        order.verify(rental).getTerms(any(), any(), eq(START), eq(END));
        var captured = ArgumentCaptor.forClass(CreateBookingAttemptCommand.class);
        order.verify(attempts).createAttempt(captured.capture());
        assertEquals(price(), captured.getValue().price());
        assertEquals(terms(), captured.getValue().terms());
        assertEquals(330_000, captured.getValue().price().amounts().deposit());
        assertEquals(770_000, captured.getValue().price().amounts().balance());
        assertEquals("DT-2611-TEST01", captured.getValue().code());
        assertEquals(CREATED, captured.getValue().createdAt());
        assertEquals(BookingDetail.fromBooking(booking()), result);
    }

    /** Replay trả snapshot lần tạo, kể cả đã huỷ; không kiểm lại báo giá hết hạn hay chạm lịch. */
    @Test
    void replaysOriginalCreationAfterCancellation() {
        existingClaim();
        when(reads.loadOwned(booking().code(), "TK-OWNER1")).thenReturn(Optional.of(cancelled(booking())));
        assertEquals(BookingDetail.fromBooking(booking()), service.create(command));
        verifyNoInteractions(quotes, vehicles, eligibility, rental, attempts);
        verify(keys, never()).replaceClaimedBookingCode(any(), any());
    }

    /** Key còn hiệu lực không được chuyển sang báo giá khác. */
    @Test
    void rejectsChangedPayloadForExistingKey() {
        existingClaim();
        var failure = assertThrows(DomainException.class,
                () -> service.create(new CreateBookingCommand("BG-2611-TEST02", command.idempotencyKey())));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        verifyNoInteractions(reads, quotes, vehicles, eligibility, rental, attempts);
    }

    /** Nhân viên không có CUSTOMER bị chặn trước chiếm key. */
    @Test
    void rejectsNonCustomer() {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("ADMIN"), null));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(DomainException.class, () -> service.create(command)).errorCode());
        verifyNoInteractions(keys, quotes, vehicles, eligibility, rental, attempts);
    }

    /** Chủ báo giá hoặc hạn sai giữ nguyên lỗi do pricing quyết định, không thử giữ chỗ. */
    @Test
    void preservesQuoteFailure() {
        var failure = DomainException.ruleViolation(ErrorCode.QUOTE_EXPIRED);
        when(quotes.getForBooking(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DomainException.class, () -> service.create(command)));
        verifyNoInteractions(vehicles, eligibility, rental, attempts);
    }

    /** Xe đã ngừng ACTIVE không được dùng dữ liệu còn sót trong báo giá. */
    @Test
    void rejectsInactiveVehicle() {
        when(vehicles.findActiveByCode("XE-TEST01")).thenReturn(Optional.empty());
        assertEquals(ErrorCode.VEHICLE_NOT_FOUND, assertThrows(DomainException.class, () -> service.create(command)).errorCode());
        verifyNoInteractions(eligibility, rental, attempts);
    }

    /** Giấy tờ không đạt dừng trước rental và giữ chỗ. */
    @Test
    void preservesEligibilityFailure() {
        var failure = DomainException.forbidden(ErrorCode.DRIVER_LICENSE_NOT_VERIFIED);
        doThrow(failure).when(eligibility).requireSelfDriveEligible(any(), anyInt(), any(), any());
        assertSame(failure, assertThrows(DomainException.class, () -> service.create(command)));
        verifyNoInteractions(rental, attempts);
    }

    /** Khoảng thuê đã không còn hợp lệ phải bị kiểm lại tại lúc đặt. */
    @Test
    void preservesRentalWindowFailure() {
        var failure = DomainException.invalidInput("The rental window is invalid.");
        when(rental.getTerms(any(), any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DomainException.class, () -> service.create(command)));
        verifyNoInteractions(attempts);
    }

    /** Trùng mã chỉ đổi mã; không tính lại giá, điều kiện hoặc hạn key. */
    @Test
    void retriesCodeCollisionWithSamePreparedSnapshot() {
        when(codes.generate("DT")).thenReturn("DT-TEST01", "DT-TEST02");
        when(attempts.createAttempt(any())).thenThrow(new BookingCodeCollisionException()).thenReturn(BookingDetail.fromBooking(booking()));
        service.create(command);
        var captured = ArgumentCaptor.forClass(CreateBookingAttemptCommand.class);
        verify(attempts, times(2)).createAttempt(captured.capture());
        var first = captured.getAllValues().getFirst();
        var second = captured.getAllValues().getLast();
        assertEquals("DT-2611-TEST02", second.code());
        assertSame(first.price(), second.price());
        assertSame(first.terms(), second.terms());
        assertSame(first.quote(), second.quote());
        assertEquals(first.createdAt(), second.createdAt());
        verify(keys).replaceClaimedBookingCode(any(), eq(second.code()));
        verify(quotes).getForBooking(any(), any(), any());
        verify(eligibility).requireSelfDriveEligible(any(), anyInt(), any(), any());
    }

    /** Tổng cộng năm lần gồm lần đầu, không lặp vô hạn và không đổi thành xe bận. */
    @Test
    void boundsCodeRetriesAtFive() {
        when(attempts.createAttempt(any())).thenThrow(new BookingCodeCollisionException());
        assertThrows(IllegalStateException.class, () -> service.create(command));
        verify(attempts, times(5)).createAttempt(any());
        verify(keys, times(4)).replaceClaimedBookingCode(any(), any());
        verify(codes, times(5)).generate("DT");
    }

    /** Chỉ unique BR-131 có ánh xạ sang lỗi khách đã có đơn chờ. */
    @Test
    void translatesPendingCustomerConflictWithoutCodeRetry() {
        when(attempts.createAttempt(any())).thenThrow(new PendingBookingExistsException(new IllegalStateException("Simulated constraint.")));
        var failure = assertThrows(DomainException.class, () -> service.create(command));
        assertEquals(ErrorCode.BOOKING_PENDING_PAYMENT_EXISTS, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        verify(attempts).createAttempt(any());
        verify(keys, never()).replaceClaimedBookingCode(any(), any());
    }

    /** Lỗi storage/deadlock đã hết lượt ở availability không được thử mã hay giả thành lỗi nghiệp vụ. */
    @Test
    void preservesUnexpectedFailureWithoutRetry() {
        var failure = new IllegalStateException("Simulated storage failure.");
        when(attempts.createAttempt(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(command)));
        verify(attempts).createAttempt(any());
        verify(keys, never()).replaceClaimedBookingCode(any(), any());
    }

    /** Claim chờ xong mới kiểm expiry báo giá bằng đồng hồ hiện tại. */
    @Test
    void checksQuoteExpiryAfterWaitingForClaim() {
        var later = CREATED.plusSeconds(901);
        // doAnswer không chạy Answer cũ với null của any() trong lúc ghi đè stub.
        doAnswer(call -> {
            when(clock.instant()).thenReturn(later);
            return new BookingIdempotencyClaim(true, call.getArgument(0));
        }).when(keys).claim(any());
        when(quotes.getForBooking(command.quoteCode(), "TK-OWNER1", later))
                .thenThrow(DomainException.ruleViolation(ErrorCode.QUOTE_EXPIRED));
        assertEquals(ErrorCode.QUOTE_EXPIRED, assertThrows(DomainException.class, () -> service.create(command)).errorCode());
        verify(quotes).getForBooking(command.quoteCode(), "TK-OWNER1", later);
        verifyNoInteractions(attempts);
    }

    /** Tháng trên mã là giờ Việt Nam; timestamp đưa vào claim không vượt độ chính xác CSDL. */
    @Test
    void usesVietnamMonthAndMicrosecondCreationTime() {
        var at = Instant.parse("2026-10-31T18:00:00.123456789Z");
        when(clock.instant()).thenReturn(at);
        when(quotes.getForBooking(any(), any(), eq(at))).thenReturn(quote("TEST01", "TK-OWNER1", "XE-TEST01"));
        service.create(command);
        var captured = ArgumentCaptor.forClass(BookingIdempotency.class);
        verify(keys).claim(captured.capture());
        assertEquals("DT-2611-TEST01", captured.getValue().bookingCode());
        assertEquals(123456000, captured.getValue().createdAt().getNano());
    }

    /** Hỏng liên kết claim không được tạo mới âm thầm rồi giữ chỗ lần nữa. */
    @Test
    void rejectsMissingReplayResult() {
        existingClaim();
        when(reads.loadOwned(any(), any())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.create(command));
        verifyNoInteractions(quotes, vehicles, eligibility, rental, attempts);
    }

    /** Cấu hình claim cũ của đúng người gọi; không đổi key chỉ vì đổi trạng thái đơn. */
    private void existingClaim() {
        doReturn(new BookingIdempotencyClaim(false,
                BookingIdempotency.create("TK-OWNER1", command.idempotencyKey(), command.quoteCode(), booking().code(), CREATED)))
                .when(keys).claim(any());
    }
}
