package com.carrental.vehicle.application.service;

import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cổng giá không lộ xe chưa lên sàn và không che lỗi toàn vẹn theo BR-010/234. */
class VehiclePricingQueryServiceTest {
    /** Chỉ ACTIVE được đọc giá, năm trạng thái còn lại đều trả rỗng. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void onlyActiveVehicleHasPublicPrice(VehicleStatus status) {
        var reads = mock(ReadVehiclePort.class);
        when(reads.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(status, 1_000_000L)));
        var result = new VehiclePricingQueryService(reads).getActivePrice(GetVehicleQuery.from("XE-TEST01"));
        assertEquals(status == VehicleStatus.ACTIVE, result.isPresent());
        if (result.isPresent()) assertEquals(1_000_000L, result.orElseThrow().dailyRate());
        verify(reads).findByCode("XE-TEST01"); verifyNoMoreInteractions(reads);
    }

    /** Không tồn tại là rỗng; ACTIVE thiếu giá là lỗi nội bộ, không giả thành xe không tồn tại. */
    @Test
    void distinguishesMissingVehicleFromInvalidActiveData() {
        var reads = mock(ReadVehiclePort.class);
        var service = new VehiclePricingQueryService(reads);
        assertTrue(service.getActivePrice(GetVehicleQuery.from("XE-TEST01")).isEmpty());
        when(reads.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(VehicleStatus.ACTIVE, null)));
        assertThrows(IllegalStateException.class, () -> service.getActivePrice(GetVehicleQuery.from("XE-TEST01")));
    }

    /** Các trường ngoài giá không đi ra hợp đồng pricing; không cần tra branchCode cho cổng này. */
    private static VehicleDetail detail(VehicleStatus status, Long rate) {
        return new VehicleDetail(1L, "XE-TEST01", "PRICE-TEST", OwnershipType.COMPANY, FuelType.PETROL, 42L,
                status, null, null, 5, Transmission.AUTOMATIC, "Toyota", "Vios", null, rate);
    }
}
