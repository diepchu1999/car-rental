package com.carrental.booking.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.ExpiredHoldRef;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.booking.application.command.ExpireBookingHoldCommand;
import com.carrental.booking.application.port.in.ExpireBookingHoldUseCase;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.application.view.ExpiredBookingHold;
import com.carrental.shared.logging.LogEventCapture;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-103/304: kiểm nhánh điều phối; transaction và câu SQL được chứng minh riêng bằng PostgreSQL. */
class BookingHoldExpiryServiceTest {
    /** Cùng tham chiếu từ hai nguồn chỉ xử lý một lần; Clock lấy một lần và cắt microsecond. */
    @Test
    void deduplicatesCandidatesAndUsesOneCutoff() {
        var reads = mock(ReadBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        var expiry = mock(ExpireBookingHoldUseCase.class);
        var clock = mock(Clock.class);
        var b = booking();
        when(clock.instant()).thenReturn(b.holdExpiresAt().plusNanos(999));
        when(reads.findExpiredPending(b.holdExpiresAt())).thenReturn(List.of(new ExpiredBookingHold(b.code(), b.reservationCode())));
        when(availability.findExpiredHolds(b.holdExpiresAt())).thenReturn(List.of(new ExpiredHoldRef(b.reservationCode(), b.code(), b.holdExpiresAt())));
        var command = new ExpireBookingHoldCommand(b.code(), b.reservationCode(), b.holdExpiresAt());
        when(expiry.expire(command)).thenReturn(true);
        assertEquals(1, new BookingHoldSweepService(reads, availability, expiry, clock).sweep());
        verify(clock).instant();
        verify(expiry).expire(command);
        verifyNoMoreInteractions(clock, expiry);
    }

    /** Một lỗi dòng không cắt vòng lặp, kết quả đếm chỉ gồm dòng thực sự đổi trạng thái. */
    @Test
    void continuesAfterFailureAndDoesNotCountSkippedRows() {
        var reads = mock(ReadBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        var expiry = mock(ExpireBookingHoldUseCase.class);
        var clock = Clock.fixed(CREATED, java.time.ZoneOffset.UTC);
        when(reads.findExpiredPending(CREATED)).thenReturn(List.of(
                new ExpiredBookingHold("DT-2611-FAILED", "KL-FAILED"),
                new ExpiredBookingHold("DT-2611-SKIP01", "KL-SKIP01"),
                new ExpiredBookingHold("DT-2611-GOOD01", "KL-GOOD01")));
        when(expiry.expire(new ExpireBookingHoldCommand("DT-2611-FAILED", "KL-FAILED", CREATED)))
                .thenThrow(new IllegalStateException("Simulated row failure."));
        when(expiry.expire(new ExpireBookingHoldCommand("DT-2611-GOOD01", "KL-GOOD01", CREATED))).thenReturn(true);
        try (var logs = new LogEventCapture(BookingHoldSweepService.class.getName())) {
            assertEquals(1, new BookingHoldSweepService(reads, availability, expiry, clock).sweep());
            verify(expiry, times(3)).expire(any());
            assertEquals(1, logs.events().size());
            assertNotNull(logs.events().getFirst().getThrowableProxy());
        }
    }

    /** Lỗi đọc cả đợt không giả thành không có việc, để scheduler ghi nhận và chạy lại lượt sau. */
    @Test
    void propagatesScanFailure() {
        var reads = mock(ReadBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        var expiry = mock(ExpireBookingHoldUseCase.class);
        var failure = new IllegalStateException("Simulated scan failure.");
        when(reads.findExpiredPending(CREATED)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new BookingHoldSweepService(reads, availability, expiry, Clock.fixed(CREATED, java.time.ZoneOffset.UTC)).sweep()));
        verifyNoInteractions(expiry);
    }

    /** Chưa tới hạn, đã huỷ hoặc thua CAS thì không gọi nhả giữ chỗ. */
    @Test
    void skipsNotDueCancelledAndLostCas() {
        var reads = mock(ReadBookingPort.class);
        var writes = mock(WriteBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        var service = new BookingHoldExpiryService(reads, writes, availability);
        var b = booking();
        when(reads.loadForExpiry(b.code())).thenReturn(Optional.of(b));
        assertFalse(service.expire(new ExpireBookingHoldCommand(b.code(), b.reservationCode(), CREATED)));
        assertFalse(service.expire(new ExpireBookingHoldCommand(b.code(), b.reservationCode(), b.holdExpiresAt())));
        when(reads.loadForExpiry(b.code())).thenReturn(Optional.of(b.cancel(b.holdExpiresAt().plusSeconds(1))));
        assertFalse(service.expire(new ExpireBookingHoldCommand(b.code(), b.reservationCode(), b.holdExpiresAt())));
        verifyNoInteractions(availability);
    }

    /** Thiếu liên kết đúng thì lỗi trước ghi; không huỷ đơn của hold khác. */
    @Test
    void rejectsMismatchedReference() {
        var reads = mock(ReadBookingPort.class);
        var writes = mock(WriteBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        when(reads.loadForExpiry(booking().code())).thenReturn(Optional.of(booking()));
        assertThrows(IllegalStateException.class, () -> new BookingHoldExpiryService(reads, writes, availability)
                .expire(new ExpireBookingHoldCommand(booking().code(), "KL-OTHER1", booking().holdExpiresAt())));
        verifyNoInteractions(writes, availability);
    }

    /** Kết quả availability không đủ điều kiện phải ném lỗi để rollback booking, hai kết quả nhả được chấp nhận. */
    @ParameterizedTest
    @EnumSource(HoldExpiryResult.class)
    void requiresReleasedHoldBeforeCommittingBooking(HoldExpiryResult result) {
        var reads = mock(ReadBookingPort.class);
        var writes = mock(WriteBookingPort.class);
        var availability = mock(AvailabilityDirectory.class);
        var b = booking();
        when(reads.loadForExpiry(b.code())).thenReturn(Optional.of(b));
        when(writes.cancelExpiredPending(any())).thenReturn(true);
        when(availability.expireHold(b.reservationCode(), b.holdExpiresAt())).thenReturn(result);
        var service = new BookingHoldExpiryService(reads, writes, availability);
        var command = new ExpireBookingHoldCommand(b.code(), b.reservationCode(), b.holdExpiresAt());
        if (result == HoldExpiryResult.NOT_ELIGIBLE) assertThrows(IllegalStateException.class, () -> service.expire(command));
        else assertTrue(service.expire(command));
        var order = inOrder(writes, availability);
        order.verify(writes).cancelExpiredPending(b.expire(b.holdExpiresAt()).orElseThrow());
        order.verify(availability).expireHold(b.reservationCode(), b.holdExpiresAt());
    }
}
