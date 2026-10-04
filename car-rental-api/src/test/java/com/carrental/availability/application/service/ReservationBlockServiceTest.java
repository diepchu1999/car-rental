package com.carrental.availability.application.service;

import com.carrental.availability.api.ReservationRef;
import com.carrental.availability.application.command.BlockReservationCommand;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm tạo BLOCKED, thử mã và dịch xung đột theo BR-104; không mô phỏng chứng minh đồng thời. */
class ReservationBlockServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private static final List<String> CODES = List.of("KL-BLOCK1", "KL-BLOCK2", "KL-BLOCK3", "KL-BLOCK4", "KL-BLOCK5");
    private WriteReservationPort writes;
    private ReadReservationPort reads;
    private ReadReservationPolicyPort policies;
    private BusinessCodeGenerator codes;
    private Clock clock;
    private ReservationCommandService service;

    /** Tạo mock có thể phát hiện mọi lần đọc giờ, đọc chính sách, tra cứu xe trống hoặc sinh mã thừa. */
    @BeforeEach
    void setUp() {
        writes = mock(WriteReservationPort.class);
        reads = mock(ReadReservationPort.class);
        policies = mock(ReadReservationPolicyPort.class);
        codes = mock(BusinessCodeGenerator.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60));
        when(codes.generate("KL")).thenReturn(CODES.get(0), CODES.get(1), CODES.get(2), CODES.get(3), CODES.get(4));
        service = new ReservationCommandService(writes, reads, policies, codes, clock);
    }

    /** Kiểm năm nguyên nhân tạo trực tiếp BLOCKED, không TTL/mã đơn/đệm và không đọc kiểm trước. */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void createsBlockedWithoutHoldPolicyOrBuffer(ReservationKind kind) {
        BlockReservationCommand command = command(kind, kind == ReservationKind.COMPLIANCE_HOLD ? null : END);
        when(writes.insert(any(Reservation.class))).thenReturn(OptionalLong.of(91L));
        assertEquals(new ReservationRef(91L, CODES.getFirst()), service.block(command));
        assertStoredFields(command, capturedWrites(1).getFirst(), CODES.getFirst());
        verify(clock).instant();
        verify(codes).generate("KL");
        verifyNoMoreInteractions(writes, codes, clock);
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm compliance đi xuyên service với cận trên null, không biến thành khoảng hữu hạn. */
    @Test
    void preservesUnboundedCompliancePeriod() {
        BlockReservationCommand command = command(ReservationKind.COMPLIANCE_HOLD, null);
        when(writes.insert(any(Reservation.class))).thenReturn(OptionalLong.of(91L));
        service.block(command);
        assertStoredFields(command, capturedWrites(1).getFirst(), CODES.getFirst());
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm chỉ thay mã sau một hoặc bốn lần trùng, giữ nguyên lý do, khoảng và mốc thời gian. */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void retriesOnlyCodeCollisionsWithFrozenFields(int collisions) {
        int[] count = {0};
        when(writes.insert(any(Reservation.class))).thenAnswer(invocation ->
                count[0]++ < collisions ? OptionalLong.empty() : OptionalLong.of(91L));
        BlockReservationCommand command = command(ReservationKind.COMPLIANCE_HOLD, null);
        assertEquals(new ReservationRef(91L, CODES.get(collisions)), service.block(command));
        List<Reservation> attempts = capturedWrites(collisions + 1);
        for (int index = 0; index < attempts.size(); index++) {
            assertStoredFields(command, attempts.get(index), CODES.get(index));
        }
        verify(clock).instant();
        verify(codes, times(collisions + 1)).generate("KL");
        verifyNoMoreInteractions(writes, codes, clock);
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm năm lần trùng mã thì dừng, không có lần chèn/sinh mã thứ sáu. */
    @Test
    void stopsAfterFiveCodeCollisions() {
        when(writes.insert(any(Reservation.class))).thenReturn(OptionalLong.empty());
        assertThrows(IllegalStateException.class, () -> service.block(command(ReservationKind.TRANSFER, END)));
        capturedWrites(5);
        verify(codes, times(5)).generate("KL");
        verify(clock).instant();
        verifyNoMoreInteractions(writes, codes, clock);
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm chồng lịch dừng ngay với VEHICLE_NOT_AVAILABLE, kể cả sau một lần trùng mã. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void stopsImmediatelyOnOverlap(int initialCollisions) {
        int[] count = {0};
        when(writes.insert(any(Reservation.class))).thenAnswer(invocation -> {
            if (count[0]++ < initialCollisions) {
                return OptionalLong.empty();
            }
            throw new ReservationOverlapException(new IllegalStateException("Database overlap."));
        });
        DomainException failure = assertThrows(DomainException.class,
                () -> service.block(command(ReservationKind.MAINTENANCE, END)));
        assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        capturedWrites(initialCollisions + 1);
        verify(codes, times(initialCollisions + 1)).generate("KL");
        verify(clock).instant();
        verifyNoMoreInteractions(writes, codes, clock);
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm lỗi lưu trữ khác truyền nguyên và không thử lại. */
    @Test
    void propagatesUnrelatedStorageFailure() {
        IllegalStateException failure = new IllegalStateException("Storage unavailable.");
        when(writes.insert(any(Reservation.class))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.block(command(ReservationKind.MAINTENANCE, END))));
        capturedWrites(1);
        verify(codes).generate("KL");
        verifyNoMoreInteractions(writes, codes);
        verifyNoInteractions(reads, policies);
    }

    /** Kiểm thiếu command bị từ chối trước mọi dependency. */
    @Test
    void rejectsNullCommandBeforeDependencies() {
        DomainException failure = assertThrows(DomainException.class, () -> service.block(null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
        verifyNoInteractions(writes, reads, policies, codes, clock);
    }

    /** Tạo command với lý do có khoảng trắng để bắt việc tự sửa nội dung. */
    private static BlockReservationCommand command(ReservationKind kind, Instant end) {
        return BlockReservationCommand.from(42L, START, end, kind, "  Workshop  ");
    }

    /** Thu aggregate của đúng số lần chèn mong đợi. */
    private List<Reservation> capturedWrites(int count) {
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(writes, times(count)).insert(captor.capture());
        return captor.getAllValues();
    }

    /** Kiểm mọi trường của khóa mới, không chỉ trạng thái hoặc số lần gọi. */
    private static void assertStoredFields(BlockReservationCommand command, Reservation actual, String code) {
        assertEquals(code, actual.code());
        assertEquals(command.vehicleId(), actual.vehicleId());
        assertEquals(command.period(), actual.period());
        assertEquals(command.kind(), actual.kind());
        assertEquals(command.reason(), actual.reason());
        assertEquals(ReservationStatus.BLOCKED, actual.status());
        assertNull(actual.bookingCode());
        assertNull(actual.holdExpiresAt());
        assertEquals(NOW, actual.createdAt());
        assertEquals(NOW, actual.statusChangedAt());
    }
}
