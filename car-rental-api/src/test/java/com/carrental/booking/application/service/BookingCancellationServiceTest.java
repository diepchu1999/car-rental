package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-301/304 và security-guideline §2: quyền kiểm tại use case, không kiểm lại điều kiện đặt xe. */
class BookingCancellationServiceTest {
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadBookingPort reads = mock(ReadBookingPort.class);
    private final WriteBookingPort writes = mock(WriteBookingPort.class);
    private final AvailabilityDirectory availability = mock(AvailabilityDirectory.class);
    private final Clock clock = Clock.fixed(CREATED.plusSeconds(60).plusNanos(999), ZoneOffset.UTC);
    private final BookingCancellationService service = new BookingCancellationService(callers, reads, writes, availability, clock);

    /** Chủ lấy từ CallerDirectory, không từ command. */
    @BeforeEach
    void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("CUSTOMER"), null));
    }

    /** Đúng chủ huỷ, mốc được đưa về micro giây; chỉ nhả chỗ sau khi thắng CAS. */
    @Test
    void cancelsOwnedBookingAndReleasesOnlyAfterWinningUpdate() {
        var original = booking();
        var cancelled = original.cancel(CREATED.plusSeconds(60));
        when(reads.loadOwned(original.code(), "TK-OWNER1")).thenReturn(Optional.of(original));
        when(writes.cancelPendingByCustomer(cancelled)).thenReturn(true);
        assertEquals(BookingDetail.fromBooking(cancelled), service.cancel(new CancelBookingCommand(original.code())));
        var order = inOrder(writes, availability);
        order.verify(writes).cancelPendingByCustomer(cancelled);
        order.verify(availability).release(original.reservationCode());
        verifyNoMoreInteractions(availability);
    }

    /** Nhân viên thuần, kể cả ADMIN, không dùng thao tác khách để huỷ đơn (BR-301). */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER", "DOCUMENT_VERIFIER"})
    void rejectsNonCustomerBeforeReading(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of(role), null));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN,
                () -> service.cancel(new CancelBookingCommand(booking().code())));
        verifyNoInteractions(reads, writes, availability);
    }

    /** Người giữ thêm vai nhân viên vẫn được tự huỷ đơn thuộc chính mình (BR-809). */
    @Test
    void acceptsCustomerWithAdditionalEmployeeRole() {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("CUSTOMER", "DOCUMENT_VERIFIER"), null));
        when(reads.loadOwned(booking().code(), "TK-OWNER1")).thenReturn(Optional.of(booking()));
        when(writes.cancelPendingByCustomer(any())).thenReturn(true);
        service.cancel(new CancelBookingCommand(booking().code()));
        verify(availability).release(booking().reservationCode());
    }

    /** Không tồn tại hoặc sai chủ đều 404; không gọi nhả chỗ hoặc ghi lịch sử. */
    @Test
    void missingOwnedBookingDoesNotWrite() {
        assertFailure(ErrorCode.BOOKING_NOT_FOUND, DomainException.Category.NOT_FOUND,
                () -> service.cancel(new CancelBookingCommand(booking().code())));
        verify(reads).loadOwned(booking().code(), "TK-OWNER1");
        verifyNoInteractions(writes, availability);
    }

    /** Bản đã huỷ bị domain chặn trước thao tác ghi hoặc nhả lần nữa. */
    @Test
    void rejectsAlreadyCancelledBooking() {
        when(reads.loadOwned(booking().code(), "TK-OWNER1"))
                .thenReturn(Optional.of(booking().cancel(CREATED.plusSeconds(30))));
        assertFailure(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, DomainException.Category.RULE_VIOLATION,
                () -> service.cancel(new CancelBookingCommand(booking().code())));
        verifyNoInteractions(writes, availability);
    }

    /** Thua CAS không được nhả chỗ của trạng thái mới hoặc giả thành huỷ thành công. */
    @Test
    void lostUpdateDoesNotReleaseReservation() {
        when(reads.loadOwned(booking().code(), "TK-OWNER1")).thenReturn(Optional.of(booking()));
        when(writes.cancelPendingByCustomer(any())).thenReturn(false);
        assertFailure(ErrorCode.BOOKING_INVALID_STATUS_TRANSITION, DomainException.Category.RULE_VIOLATION,
                () -> service.cancel(new CancelBookingCommand(booking().code())));
        verifyNoInteractions(availability);
    }

    /** Lỗi nhả chỗ không bị nuốt hoặc đổi thành response thành công; proxy ngoài sẽ rollback. */
    @Test
    void propagatesReleaseFailure() {
        when(reads.loadOwned(booking().code(), "TK-OWNER1")).thenReturn(Optional.of(booking()));
        when(writes.cancelPendingByCustomer(any())).thenReturn(true);
        var failure = new IllegalStateException("Simulated release failure.");
        doThrow(failure).when(availability).release(booking().reservationCode());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.cancel(new CancelBookingCommand(booking().code()))));
    }

    /** Command null là lỗi đầu vào, không đi vào đọc/ghi. */
    @Test
    void rejectsMissingCommand() {
        assertFailure(ErrorCode.INVALID_REQUEST, DomainException.Category.INVALID_INPUT, () -> service.cancel(null));
        verifyNoInteractions(reads, writes, availability);
    }

    /** Kiểm cả mã và nhóm lỗi, không chỉ có exception. */
    private static void assertFailure(ErrorCode code, DomainException.Category category, Runnable action) {
        var failure = assertThrows(DomainException.class, action::run);
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
