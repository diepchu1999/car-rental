package com.carrental.availability.application.service;

import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.shared.code.BusinessCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối dọn hết hạn dùng một mốc Clock, không tính lại chính sách BR-103. */
class ReservationExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00Z");
    private WriteReservationPort writes;
    private ReadReservationPort reads;
    private ReadReservationPolicyPort policies;
    private BusinessCodeGenerator codes;
    private Clock clock;
    private ReservationCommandService service;

    /** Tạo service với các dependency có thể quan sát số lần gọi. */
    @BeforeEach
    void setUp() {
        writes = mock(WriteReservationPort.class);
        reads = mock(ReadReservationPort.class);
        policies = mock(ReadReservationPolicyPort.class);
        codes = mock(BusinessCodeGenerator.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(30));
        service = new ReservationCommandService(writes, reads, policies, codes, clock);
    }

    /** Kiểm một mốc duy nhất, trả đúng số dòng và không đọc aggregate, TTL hoặc sinh mã. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void usesOneClockInstantAndReturnsAffectedCount(int count) {
        when(writes.releaseExpiredHolds(NOW)).thenReturn(count);
        assertEquals(count, service.expireHolds());
        verify(clock).instant();
        verify(writes).releaseExpiredHolds(NOW);
        verifyNoMoreInteractions(clock, writes);
        verifyNoInteractions(reads, policies, codes);
    }

    /** Kiểm lượt kế tiếp lấy mốc mới thay vì đóng băng mốc khi khởi động. */
    @Test
    void refreshesCutoffForEachSweep() {
        service.expireHolds();
        service.expireHolds();
        verify(writes).releaseExpiredHolds(NOW);
        verify(writes).releaseExpiredHolds(NOW.plusSeconds(30));
        verify(clock, times(2)).instant();
        verifyNoMoreInteractions(clock, writes);
    }

    /** Kiểm lỗi truyền nguyên trạng để transaction rollback, không thử lại hoặc giả số không. */
    @Test
    void propagatesFailureWithoutRetry() {
        var failure = new IllegalStateException("Storage unavailable.");
        when(writes.releaseExpiredHolds(NOW)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, service::expireHolds));
        verify(writes).releaseExpiredHolds(NOW);
        verifyNoMoreInteractions(writes);
    }

    /** Kiểm use case khai báo transaction ghi REQUIRED, không REQUIRES_NEW. */
    @Test
    void declaresRequiredWriteTransaction() throws Exception {
        var annotation = ReservationCommandService.class.getMethod("expireHolds")
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertFalse(annotation.readOnly());
        assertEquals(Propagation.REQUIRED, annotation.propagation());
    }
}
