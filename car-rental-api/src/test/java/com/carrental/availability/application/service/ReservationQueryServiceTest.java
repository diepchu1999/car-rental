package com.carrental.availability.application.service;

import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.query.ListBusyVehiclesQuery;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối đọc và cộng đệm; SQL thật được kiểm trong integration test riêng. */
class ReservationQueryServiceTest {

    private static final Instant START = Instant.parse("2030-10-01T03:00:00Z");
    private static final Instant END = START.plusSeconds(7200);
    private ReadReservationPort reads;
    private ReservationQueryService service;

    /** Tạo service với duy nhất cổng đọc, không có cổng ghi hoặc đồng hồ. */
    @BeforeEach
    void setUp() {
        reads = mock(ReadReservationPort.class);
        service = new ReservationQueryService(reads);
    }

    /** Kiểm đệm 0, 1, 2 giờ cộng đúng một lần vào cuối khoảng và giữ nguyên tập ứng viên. */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2})
    void appliesBufferExactlyOnce(long hours) {
        var query = ListBusyVehiclesQuery.from(START, END, Duration.ofHours(hours), List.of(41L, 42L));
        var expected = ReservationPeriod.finite(START, END.plus(Duration.ofHours(hours)));
        var storageResult = new HashSet<>(Set.of(42L));
        when(reads.findBusyVehicleIds(expected, query.candidateIds())).thenReturn(storageResult);
        Set<Long> actual = service.listBusyVehicleIds(query);
        storageResult.clear();
        assertEquals(Set.of(42L), actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.add(41L));
        verify(reads).findBusyVehicleIds(expected, query.candidateIds());
        verifyNoMoreInteractions(reads);
    }

    /** Kiểm tập ứng viên rỗng trả rỗng và không gọi persistence. */
    @Test
    void skipsStorageForEmptyCandidates() {
        assertTrue(service.listBusyVehicleIds(
                ListBusyVehiclesQuery.from(START, END, Duration.ZERO, List.of())).isEmpty());
        verifyNoInteractions(reads);
    }

    /** Kiểm thiếu query bị từ chối trước khi đọc dữ liệu. */
    @Test
    void rejectsNullQuery() {
        assertThrows(DomainException.class, () -> service.listBusyVehicleIds(null));
        verifyNoInteractions(reads);
    }

    /** Kiểm tràn thời gian bị từ chối ngay cả khi không có xe ứng viên. */
    @Test
    void rejectsOverflowBeforeEmptyShortcut() {
        var query = ListBusyVehiclesQuery.from(Instant.MAX.minusSeconds(1), Instant.MAX,
                Duration.ofSeconds(1), List.of());
        assertThrows(DomainException.class, () -> service.listBusyVehicleIds(query));
        verifyNoInteractions(reads);
    }

    /** Kiểm lỗi lưu trữ không bị biến thành danh sách xe bận rỗng. */
    @Test
    void propagatesStorageFailure() {
        var query = ListBusyVehiclesQuery.from(START, END, Duration.ZERO, List.of(41L));
        var failure = new IllegalStateException("Storage unavailable.");
        when(reads.findBusyVehicleIds(query.rentalPeriod(), query.candidateIds())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.listBusyVehicleIds(query)));
        verify(reads).findBusyVehicleIds(query.rentalPeriod(), query.candidateIds());
        verifyNoMoreInteractions(reads);
    }

    /** Kiểm use case công bố transaction chỉ đọc và tham gia transaction bên gọi. */
    @Test
    void declaresReadOnlyRequiredTransaction() throws Exception {
        Transactional annotation = ReservationQueryService.class
                .getMethod("listBusyVehicleIds", ListBusyVehiclesQuery.class).getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertTrue(annotation.readOnly());
        assertEquals(Propagation.REQUIRED, annotation.propagation());
    }
}
