package com.carrental.vehicle.adapter.in.internal;

import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.port.in.GetActiveVehicleForBookingUseCase;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleBookingDetail;
import com.carrental.vehicle.domain.Transmission;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cổng cross-module chỉ ánh xạ thuộc tính và giữ nguyên kết quả/ngoại lệ của use case (ADR-0008). */
class VehicleDirectoryAdapterTest {
    /** Không lộ enum domain; ID dùng nội bộ, mã chi nhánh lấy nguyên từ kết quả use case. */
    @Test
    void mapsOnlyRequiredBookingAttributes() {
        var useCase = mock(GetActiveVehicleForBookingUseCase.class);
        when(useCase.getActiveForBooking(GetVehicleQuery.from("XE-TEST01"))).thenReturn(Optional.of(
                new VehicleBookingDetail(91L, "XE-TEST01", "CN-TEST01", 7, Transmission.AUTOMATIC)));
        var ref = new VehicleDirectoryAdapter(useCase).findActiveByCode("XE-TEST01").orElseThrow();
        assertEquals(91L, ref.id());
        assertEquals("XE-TEST01", ref.code());
        assertEquals("CN-TEST01", ref.branchCode());
        assertEquals(7, ref.seats());
        assertEquals("AUTOMATIC", ref.transmission());
        verify(useCase).getActiveForBooking(GetVehicleQuery.from("XE-TEST01"));
        verifyNoMoreInteractions(useCase);
    }

    /** Rỗng là rỗng; lỗi use case không bị nuốt ở adapter. */
    @Test
    void preservesAbsenceAndFailures() {
        var useCase = mock(GetActiveVehicleForBookingUseCase.class);
        var adapter = new VehicleDirectoryAdapter(useCase);
        assertTrue(adapter.findActiveByCode("XE-TEST01").isEmpty());
        var failure = new IllegalStateException("Storage unavailable.");
        when(useCase.getActiveForBooking(GetVehicleQuery.from("XE-TEST01"))).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> adapter.findActiveByCode("XE-TEST01")));
    }

    /** Mã thiếu bị query chặn trước khi gọi use case. */
    @Test
    void rejectsBlankCode() {
        var useCase = mock(GetActiveVehicleForBookingUseCase.class);
        assertThrows(DomainException.class, () -> new VehicleDirectoryAdapter(useCase).findActiveByCode(" "));
        verifyNoInteractions(useCase);
    }
}
