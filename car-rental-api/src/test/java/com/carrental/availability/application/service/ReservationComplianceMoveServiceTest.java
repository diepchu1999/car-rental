package com.carrental.availability.application.service;

import com.carrental.availability.application.command.MoveComplianceHoldStartCommand;
import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối BR-015: domain kiểm mốc, CAS đúng mốc cũ, không đọc Clock hay thử lại use case. */
class ReservationComplianceMoveServiceTest {
    private static final String CODE = "KL-MOVE01";
    private static final Instant START = Instant.parse("2030-10-01T00:00:00Z");
    private static final Instant NEXT = START.plusSeconds(86400);
    private ReadReservationPort reads;
    private WriteReservationPort writes;
    private ReadReservationPolicyPort policies;
    private BusinessCodeGenerator codes;
    private Clock clock;
    private ReservationCommandService service;

    /** Tạo dependency giả để phát hiện mọi lần gọi ngoài đọc aggregate và ghi CAS. */
    @BeforeEach
    void setUp() {
        reads = mock(ReadReservationPort.class);
        writes = mock(WriteReservationPort.class);
        policies = mock(ReadReservationPolicyPort.class);
        codes = mock(BusinessCodeGenerator.class);
        clock = mock(Clock.class);
        service = new ReservationCommandService(writes, reads, policies, codes, clock);
    }

    /** Kiểm CAS nhận đúng mốc cũ/mới, không sinh mã, lấy giờ hay đọc chính sách. */
    @Test
    void movesUsingOriginalStartWithoutClockOrPolicy() {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(compliance()));
        when(writes.moveComplianceHoldStart(CODE, START, NEXT)).thenReturn(true);
        service.moveComplianceHoldStart(command());
        verify(reads).loadAggregate(CODE);
        verify(writes).moveComplianceHoldStart(CODE, START, NEXT);
        verifyNoMoreInteractions(reads, writes);
        verifyNoInteractions(clock, policies, codes);
    }

    /** Kiểm mốc cũ không còn khớp trả đúng nhóm CONFLICT, không đọc lại và ghi đè bên thắng. */
    @Test
    void reportsStaleStartAsConflictWithoutRetry() {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(compliance()));
        when(writes.moveComplianceHoldStart(CODE, START, NEXT)).thenReturn(false);
        DomainException failure = assertThrows(DomainException.class, () -> service.moveComplianceHoldStart(command()));
        assertEquals(ErrorCode.RESERVATION_INVALID_STATUS_TRANSITION, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        verify(reads).loadAggregate(CODE);
        verify(writes).moveComplianceHoldStart(CODE, START, NEXT);
        verifyNoMoreInteractions(reads, writes);
        verifyNoInteractions(clock, policies, codes);
    }

    /** Kiểm không tìm thấy mã trả NOT_FOUND, không ghi hoặc tạo khóa thay thế. */
    @Test
    void rejectsMissingReservation() {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.empty());
        DomainException failure = assertThrows(DomainException.class, () -> service.moveComplianceHoldStart(command()));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verifyNoInteractions(writes, clock, policies, codes);
    }

    /** Kiểm thiếu command bị chặn trước mọi dependency. */
    @Test
    void rejectsNullCommand() {
        DomainException failure = assertThrows(DomainException.class, () -> service.moveComplianceHoldStart(null));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(reads, writes, clock, policies, codes);
    }

    /** Kiểm mốc bằng/lùi bị domain chặn trước cổng ghi. */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonIncreasingStartBeforeWrite(long offset) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(compliance()));
        DomainException failure = assertThrows(DomainException.class, () -> service.moveComplianceHoldStart(
                MoveComplianceHoldStartCommand.from(CODE, START.plusSeconds(offset))));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        verifyNoInteractions(writes, clock, policies, codes);
    }

    /** Kiểm lỗi lưu trữ truyền nguyên, không đổi thành CONFLICT và không thử lại tại service. */
    @Test
    void propagatesWriteFailure() {
        var failure = new IllegalStateException("Storage unavailable.");
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(compliance()));
        when(writes.moveComplianceHoldStart(CODE, START, NEXT)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.moveComplianceHoldStart(command())));
        verify(writes).moveComplianceHoldStart(CODE, START, NEXT);
        verifyNoMoreInteractions(writes);
    }

    /** Cung cấp command hợp lệ để mỗi test chỉ thay đổi một điều kiện. */
    private static MoveComplianceHoldStartCommand command() {
        return MoveComplianceHoldStartCommand.from(CODE, NEXT);
    }

    /** Cung cấp aggregate có cận trên không chặn và trạng thái BLOCKED. */
    private static Reservation compliance() {
        return Reservation.createBlocked(CODE, 42L, ReservationPeriod.unboundedFrom(START),
                ReservationKind.COMPLIANCE_HOLD, null, START.minusSeconds(86400));
    }
}
