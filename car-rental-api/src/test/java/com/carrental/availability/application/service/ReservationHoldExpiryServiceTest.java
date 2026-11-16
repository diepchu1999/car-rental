package com.carrental.availability.application.service;

import com.carrental.availability.api.ExpiredHoldRef;
import com.carrental.availability.api.HoldExpiryResult;
import com.carrental.availability.application.command.ExpireReservationHoldCommand;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.application.query.ListExpiredReservationHoldsQuery;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-103: điều phối hết hạn từng hold, đầu vào và kết quả tường minh cho bên gọi. */
class ReservationHoldExpiryServiceTest {
    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00Z");
    private static final String CODE = "KL-ONE001";
    private ReadReservationPort reads;
    private WriteReservationPort writes;
    private ReservationHoldExpiryService service;

    /** Tách unit test khỏi bằng chứng transaction/PostgreSQL ở integration test. */
    @BeforeEach
    void setUp() {
        reads = mock(ReadReservationPort.class);
        writes = mock(WriteReservationPort.class);
        service = new ReservationHoldExpiryService(reads, writes);
    }

    /** Ghi có điều kiện trước, không đọc-rồi-ghi để quyết định có nhả hay không. */
    @Test
    void returnsReleasedWithoutReadingAfterSuccessfulWrite() {
        when(writes.releaseExpiredHold(CODE, NOW)).thenReturn(true);
        assertEquals(HoldExpiryResult.RELEASED, service.expire(new ExpireReservationHoldCommand(CODE, NOW)));
        verify(writes).releaseExpiredHold(CODE, NOW);
        verifyNoInteractions(reads);
    }

    /** Chỉ RELEASED có hạn đã tới được coi là đã nhả; các trạng thái khác không thành công giả. */
    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void distinguishesStoredStatusWhenConditionalWriteDoesNothing(ReservationStatus status) {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.of(saved(status, NOW)));
        assertEquals(status == ReservationStatus.RELEASED ? HoldExpiryResult.ALREADY_RELEASED
                        : HoldExpiryResult.NOT_ELIGIBLE,
                service.expire(new ExpireReservationHoldCommand(CODE, NOW)));
        var order = inOrder(writes, reads);
        order.verify(writes).releaseExpiredHold(CODE, NOW);
        order.verify(reads).loadAggregate(CODE);
        order.verifyNoMoreInteractions();
    }

    /** RELEASED do huỷ sớm hoặc bản cũ không có hạn không được giả là đã hết hạn ở mốc đang xét. */
    @Test
    void releasedWithoutReachedDeadlineIsNotEligible() {
        for (Instant deadline : new Instant[]{null, NOW.plusNanos(1000)}) {
            when(reads.loadAggregate(CODE)).thenReturn(Optional.of(saved(ReservationStatus.RELEASED, deadline)));
            assertEquals(HoldExpiryResult.NOT_ELIGIBLE, service.expire(new ExpireReservationHoldCommand(CODE, NOW)));
        }
    }

    /** Mã không tồn tại báo lỗi rõ ràng; bên gọi không được commit đơn với chỗ giữ bị mất. */
    @Test
    void missingHoldIsNotFound() {
        when(reads.loadAggregate(CODE)).thenReturn(Optional.empty());
        var failure = assertThrows(DomainException.class,
                () -> service.expire(new ExpireReservationHoldCommand(CODE, NOW)));
        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** Lỗi ghi truyền nguyên trạng cho giao dịch ngoài rollback, không đổi thành NOT_ELIGIBLE. */
    @Test
    void propagatesWriteFailure() {
        var failure = new IllegalStateException("Simulated storage failure.");
        when(writes.releaseExpiredHold(CODE, NOW)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.expire(new ExpireReservationHoldCommand(CODE, NOW))));
        verifyNoInteractions(reads);
    }

    /** Danh sách là bản sao bất biến, giữ mã đơn và hạn đã lưu, không nhả chỗ trong lúc đọc. */
    @Test
    void returnsImmutableCandidateSnapshotWithoutWriting() {
        var candidate = new ExpiredHoldRef(CODE, "DT-3010-ONE001", NOW);
        var source = new ArrayList<>(List.of(candidate));
        when(reads.findExpiredHolds(NOW)).thenReturn(source);
        var result = service.list(new ListExpiredReservationHoldsQuery(NOW));
        source.clear();
        assertEquals(List.of(candidate), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
        verifyNoInteractions(writes);
    }

    /** Lỗi đọc danh sách không biến thành lượt dọn rỗng. */
    @Test
    void propagatesCandidateReadFailure() {
        var failure = new IllegalStateException("Simulated read failure.");
        when(reads.findExpiredHolds(NOW)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.list(new ListExpiredReservationHoldsQuery(NOW))));
    }

    /** Hai đầu vào cùng cắt xuống microsecond; không làm tròn khiến nhả sớm. */
    @Test
    void floorsCutoffConsistently() {
        var cutoff = NOW.minusNanos(1);
        var expected = NOW.minusNanos(1000);
        assertEquals(expected, new ExpireReservationHoldCommand(CODE, cutoff).expiredAt());
        assertEquals(expected, new ListExpiredReservationHoldsQuery(cutoff).expiredAt());
    }

    /** Mã rỗng bị chặn trước persistence; không nhả một dòng ngầm định. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingCode(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> new ExpireReservationHoldCommand(code, NOW)).errorCode());
    }

    /** Mốc dọn/command/query bắt buộc; không tự dùng đồng hồ hoặc TTL thay thế. */
    @Test
    void rejectsMissingInputs() {
        assertThrows(DomainException.class, () -> new ExpireReservationHoldCommand(CODE, null));
        assertThrows(DomainException.class, () -> new ListExpiredReservationHoldsQuery(null));
        assertThrows(DomainException.class, () -> service.expire(null));
        assertThrows(DomainException.class, () -> service.list(null));
        verifyNoInteractions(reads, writes);
    }

    /** Khôi phục trạng thái hợp lệ, kể cả khoá vận hành không có hạn hoặc mã đơn. */
    private static Reservation saved(ReservationStatus status, Instant deadline) {
        boolean operational = status == ReservationStatus.BLOCKED;
        return Reservation.restore(CODE, 1L, ReservationPeriod.finite(NOW.plusSeconds(3600), NOW.plusSeconds(7200)),
                operational ? ReservationKind.MAINTENANCE : ReservationKind.RENTAL, status,
                operational ? null : "DT-3010-ONE001", null, operational ? null : deadline,
                NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    }
}
