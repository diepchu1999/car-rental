package com.carrental.booking.application.service;

import com.carrental.availability.api.*;
import com.carrental.booking.application.command.CreateBookingAttemptCommand;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.BookingDetail;
import com.carrental.booking.domain.Booking;
import com.carrental.shared.error.*;
import com.carrental.vehicle.api.VehicleRef;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static com.carrental.booking.application.service.BookingCreationFixtures.quote;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm ánh xạ lệnh/hold/snapshot; rollback savepoint được chứng minh trong integration test riêng. */
class BookingCreationAttemptServiceTest {
    private final AvailabilityDirectory availability = mock(AvailabilityDirectory.class);
    private final WriteBookingPort writes = mock(WriteBookingPort.class);
    private final ReadBookingPort reads = mock(ReadBookingPort.class);
    private final BookingCreationAttemptService service = new BookingCreationAttemptService(availability, writes, reads);
    private final CreateBookingAttemptCommand command = new CreateBookingAttemptCommand("DT-2611-TEST01",
            quote("TEST01", "TK-OWNER1", "XE-TEST01"), new VehicleRef(42, "XE-TEST01", "CN-TEST01", 4, "AUTOMATIC"),
            price(), terms(), CREATED);

    /** Hạn cố ý 37 phút để phát hiện service tự đặt lại một giờ thay cho giá trị availability trả ra. */
    @BeforeEach
    void setUp() {
        when(availability.hold(anyLong(), any(), any(), any()))
                .thenReturn(new ReservationRef(51, "KL-TEST01", CREATED.plusSeconds(2220)));
        when(writes.insert(any())).thenReturn(true);
        when(reads.loadOwned("DT-2611-TEST01", "TK-OWNER1")).thenReturn(Optional.of(booking()));
    }

    /** Giờ trả chưa cộng đệm, mã booking và hạn thực tế đều qua đúng cổng, reload trước khi trả. */
    @Test
    void preservesActualHoldAndReturnsReloadedSnapshot() {
        assertEquals(BookingDetail.fromBooking(booking()), service.createAttempt(command));
        var order = inOrder(availability, writes, reads);
        order.verify(availability).hold(42, new Period(START, END), terms().turnaroundBuffer(), command.code());
        var captured = ArgumentCaptor.forClass(Booking.class);
        order.verify(writes).insert(captured.capture());
        assertEquals(booking(), captured.getValue());
        order.verify(reads).loadOwned(command.code(), "TK-OWNER1");
    }

    /** Trùng mã là tín hiệu rollback savepoint, không xem như thành công của đơn cũ. */
    @Test
    void reportsCodeCollisionWithoutReloadingOldBooking() {
        when(writes.insert(any())).thenReturn(false);
        assertThrows(BookingCodeCollisionException.class, () -> service.createAttempt(command));
        verifyNoInteractions(reads);
    }

    /** Xe bận không được ghi header hoặc biến thành vòng thử mã. */
    @Test
    void preservesVehicleConflictBeforeAnyBookingWrite() {
        var failure = DomainException.conflict(ErrorCode.VEHICLE_NOT_AVAILABLE);
        when(availability.hold(anyLong(), any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(DomainException.class, () -> service.createAttempt(command)));
        verifyNoInteractions(writes, reads);
    }

    /** Đã ghi nhưng không đọc lại được phải ném lỗi để transaction bên ngoài rollback. */
    @Test
    void rejectsMissingStoredResult() {
        when(reads.loadOwned(any(), any())).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.createAttempt(command));
    }
}
