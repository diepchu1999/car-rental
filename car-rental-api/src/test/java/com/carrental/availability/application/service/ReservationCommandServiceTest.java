package com.carrental.availability.application.service;

import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.HoldReservationCommand;
import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kiểm điều phối giữ chỗ và giới hạn thử mã theo BR-103, BR-104, BR-109, BR-116.
 * Test cô lập không chứng minh transaction hoặc SQL; phần đó dùng PostgreSQL thật.
 */
class ReservationCommandServiceTest {

    private static final Instant NOW = Instant.parse("2030-09-01T00:00:00Z");
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final List<String> CODES = List.of(
            "KL-AAAAAA", "KL-BBBBBB", "KL-CCCCCC", "KL-DDDDDD", "KL-EEEEEE"
    );

    private WriteReservationPort writes;
    private ReadReservationPort reads;
    private ReadReservationPolicyPort policies;
    private BusinessCodeGenerator codes;
    private Clock clock;
    private ReservationCommandService service;

    /** Tạo các dependency có thể đếm số lần gọi để phát hiện đọc lại hoặc thử lại sai. */
    @BeforeEach
    void setUp() {
        writes = mock(WriteReservationPort.class);
        reads = mock(ReadReservationPort.class);
        policies = mock(ReadReservationPolicyPort.class);
        codes = mock(BusinessCodeGenerator.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW).thenReturn(NOW.plusSeconds(600));
        when(policies.holdDuration(NOW)).thenReturn(Duration.ofMinutes(90));
        when(codes.generate("KL")).thenReturn(CODES.get(0), CODES.get(1), CODES.get(2), CODES.get(3), CODES.get(4));
        service = new ReservationCommandService(writes, reads, policies, codes, clock);
    }

    /**
     * Kiểm cộng đệm đúng một lần, đọc Clock/chính sách cùng mốc và trả đúng định danh.
     *
     * @param bufferHours số giờ đệm đã được bên gọi phân giải
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void createsHeldWithFrozenPolicyAndBuffer(long bufferHours) {
        when(writes.insert(any(Reservation.class))).thenReturn(OptionalLong.of(91L));
        assertEquals(new ReservationRef(91L, CODES.getFirst()), service.hold(command(bufferHours)));
        Reservation saved = capturedWrites(1).getFirst();
        verifyNoInteractions(reads);
        assertEquals(42L, saved.vehicleId());
        assertEquals("booking-code", saved.bookingCode());
        assertEquals(ReservationKind.RENTAL, saved.kind());
        assertEquals(ReservationStatus.HELD, saved.status());
        assertEquals(START, saved.period().startInclusive());
        assertEquals(END.plus(Duration.ofHours(bufferHours)), saved.period().endExclusive());
        assertEquals(NOW, saved.createdAt());
        assertEquals(NOW, saved.statusChangedAt());
        assertEquals(NOW.plus(Duration.ofMinutes(90)), saved.holdExpiresAt());
        assertNull(saved.reason());
        verify(clock).instant();
        verify(policies).holdDuration(NOW);
        verify(codes).generate("KL");
        verifyNoMoreInteractions(clock, policies, codes, writes);
    }

    /**
     * Kiểm chỉ trùng mã mới thử lại; hạn, mốc tạo và khoảng không đổi giữa các lần.
     *
     * @param collisions số lần trùng trước khi chèn thành công
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void retriesOnlyCodeCollisionsWithoutExtendingHold(int collisions) {
        int[] calls = {0};
        when(writes.insert(any(Reservation.class))).thenAnswer(invocation ->
                calls[0]++ < collisions ? OptionalLong.empty() : OptionalLong.of(91L));
        assertEquals(new ReservationRef(91L, CODES.get(collisions)), service.hold(command(2)));
        List<Reservation> attempts = capturedWrites(collisions + 1);
        for (int index = 0; index < attempts.size(); index++) {
            Reservation saved = attempts.get(index);
            assertEquals(CODES.get(index), saved.code());
            assertEquals(NOW, saved.createdAt());
            assertEquals(NOW, saved.statusChangedAt());
            assertEquals(NOW.plus(Duration.ofMinutes(90)), saved.holdExpiresAt());
            assertEquals(START, saved.period().startInclusive());
            assertEquals(END.plusSeconds(7200), saved.period().endExclusive());
            assertEquals(42L, saved.vehicleId());
            assertEquals("booking-code", saved.bookingCode());
            assertEquals(ReservationKind.RENTAL, saved.kind());
            assertEquals(ReservationStatus.HELD, saved.status());
        }
        verify(clock).instant();
        verify(policies).holdDuration(NOW);
        verify(codes, times(collisions + 1)).generate("KL");
        verifyNoMoreInteractions(clock, policies, codes, writes);
    }

    /** Kiểm hết năm lần trùng mã thì báo lỗi nội bộ, không có lần sinh/chèn thứ sáu. */
    @Test
    void stopsAfterFiveCodeCollisions() {
        when(writes.insert(any(Reservation.class))).thenReturn(OptionalLong.empty());
        assertThrows(IllegalStateException.class, () -> service.hold(command(0)));
        capturedWrites(5);
        verify(codes, times(5)).generate("KL");
        verify(clock).instant();
        verify(policies).holdDuration(NOW);
        verifyNoMoreInteractions(clock, policies, codes, writes);
    }

    /**
     * Kiểm xe bận dừng ngay, kể cả trước đó từng trùng mã; đúng VEHICLE_NOT_AVAILABLE/CONFLICT.
     *
     * @param initialCollisions số xung đột mã trước khi gặp xe bận
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void stopsImmediatelyOnVehicleOverlap(int initialCollisions) {
        int[] calls = {0};
        when(writes.insert(any(Reservation.class))).thenAnswer(invocation -> {
            if (calls[0]++ < initialCollisions) {
                return OptionalLong.empty();
            }
            throw new ReservationOverlapException(new IllegalStateException("Database overlap."));
        });
        DomainException failure = assertThrows(DomainException.class, () -> service.hold(command(0)));
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        capturedWrites(initialCollisions + 1);
        verify(codes, times(initialCollisions + 1)).generate("KL");
        verify(clock).instant();
        verify(policies).holdDuration(NOW);
        verifyNoMoreInteractions(clock, policies, codes, writes);
    }

    /** Kiểm lỗi lưu trữ khác giữ nguyên, không bị coi là trùng mã hoặc xe bận. */
    @Test
    void propagatesUnrelatedStorageFailureWithoutRetry() {
        IllegalStateException storageFailure = new IllegalStateException("Storage unavailable.");
        when(writes.insert(any(Reservation.class))).thenThrow(storageFailure);
        assertSame(storageFailure, assertThrows(IllegalStateException.class, () -> service.hold(command(0))));
        capturedWrites(1);
        verify(codes).generate("KL");
        verifyNoMoreInteractions(writes, codes);
    }

    /** Kiểm thiếu command bị từ chối trước mọi tương tác với dependency. */
    @Test
    void rejectsNullCommandBeforeAccessingDependencies() {
        assertThrows(DomainException.class, () -> service.hold(null));
        verifyNoInteractions(writes, policies, codes, clock);
    }

    /** Kiểm cộng đệm tràn miền thời gian bị từ chối trước khi lấy Clock hoặc ghi. */
    @Test
    void rejectsBufferOverflowBeforeAccessingDependencies() {
        HoldReservationCommand command = HoldReservationCommand.from(42L, Instant.MAX.minusSeconds(1),
                Instant.MAX, Duration.ofSeconds(1), "booking-code");
        assertThrows(DomainException.class, () -> service.hold(command));
        verifyNoInteractions(writes, policies, codes, clock);
    }

    /** Tạo đầu vào hợp lệ với khoảng chưa cộng đệm. */
    private static HoldReservationCommand command(long bufferHours) {
        return HoldReservationCommand.from(42L, START, END, Duration.ofHours(bufferHours), "booking-code");
    }

    /** Thu tất cả aggregate đã gửi xuống cổng ghi và kiểm số lần chèn chính xác. */
    private List<Reservation> capturedWrites(int expectedCount) {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(writes, times(expectedCount)).insert(captor.capture());
        return captor.getAllValues();
    }
}
