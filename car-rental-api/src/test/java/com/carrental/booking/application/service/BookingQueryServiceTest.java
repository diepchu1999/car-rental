package com.carrental.booking.application.service;

import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.application.query.GetBookingQuery;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.Booking;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm quyền ngay tại use case và không dùng Clock/config khi đọc đơn (ADR-0014). */
class BookingQueryServiceTest {
    private final ReadBookingPort reads = mock(ReadBookingPort.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final BookingQueryService service = new BookingQueryService(reads, callers);

    /** Danh tính CUSTOMER thật về mặt hợp đồng, không nhận chủ từ query. */
    @BeforeEach
    void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of("CUSTOMER"), null));
    }

    /** GET phải trả trạng thái đang lưu, không dùng snapshot PENDING dành riêng cho phát lại POST. */
    @Test
    void returnsCurrentCancelledStatusWithUnchangedSnapshot() {
        var original = booking();
        var cancelled = new Booking(original.code(), original.customerAccountCode(), original.quoteCode(),
                original.vehicleCode(), original.branchCode(), original.reservationCode(), original.startAt(), original.endAt(),
                original.rentalType(), original.driveMode(), original.pickupMethod(), original.price(), original.rentalTerms(),
                original.holdExpiresAt(), BookingStatus.CANCELLED, original.createdAt(), CREATED.plusSeconds(60));
        when(reads.loadOwned(original.code(), "TK-OWNER1")).thenReturn(Optional.of(cancelled));
        assertEquals(BookingDetail.fromBooking(cancelled), service.get(GetBookingQuery.from(original.code())));
        verify(reads).loadOwned(original.code(), "TK-OWNER1");
        verifyNoMoreInteractions(reads);
    }

    /** Mã không tồn tại hoặc không tìm thấy trong phạm vi chủ đều cùng BOOKING_NOT_FOUND/404. */
    @ParameterizedTest @ValueSource(strings = {"missing-booking", "DT-2611-OTHER1"})
    void returnsNotFoundWithinCallerScope(String code) {
        var failure = assertThrows(DomainException.class, () -> service.get(GetBookingQuery.from(code)));
        assertEquals(ErrorCode.BOOKING_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verify(reads).loadOwned(code, "TK-OWNER1");
    }

    /** Quyền nhân viên không vượt qua kiểm chủ bằng cách gọi thẳng port/in. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "DOCUMENT_VERIFIER"})
    void deniesNonCustomerBeforeReading(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OWNER1", Set.of(role), null));
        var failure = assertThrows(DomainException.class, () -> service.get(GetBookingQuery.from(booking().code())));
        assertEquals(ErrorCode.ACCESS_DENIED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        verifyNoInteractions(reads);
    }

    /** Ngoài request không giả danh khách và không truy vấn dữ liệu. */
    @Test
    void preservesMissingCallerFailure() {
        var failure = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        when(callers.current()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.get(GetBookingQuery.from(booking().code()))));
        verifyNoInteractions(reads);
    }
}
