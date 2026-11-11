package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.shared.error.DomainException;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.VehicleStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cổng đặt đơn chỉ đọc xe ACTIVE, không yêu cầu quyền nhân viên hoặc suy mã từ ID (BR-010/105/128). */
class VehicleBookingQueryServiceTest {
    /** Mọi trạng thái khác ACTIVE đều rỗng và không tra chi nhánh dư thừa. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void onlyActiveVehicleIsAvailableForBookingLookup(VehicleStatus status) {
        var reads = mock(ReadVehiclePort.class);
        var branches = mock(BranchDirectory.class);
        when(reads.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(status, 42L, 7, Transmission.AUTOMATIC)));
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-BRANCH")));
        var result = new VehicleBookingQueryService(reads, branches).getActiveForBooking(GetVehicleQuery.from("XE-TEST01"));
        assertEquals(status == VehicleStatus.ACTIVE, result.isPresent());
        if (result.isPresent()) {
            var vehicle = result.orElseThrow();
            assertEquals(91L, vehicle.id());
            assertEquals("XE-TEST01", vehicle.code());
            assertEquals("CN-BRANCH", vehicle.branchCode());
            assertEquals(7, vehicle.seats());
            assertEquals(Transmission.AUTOMATIC, vehicle.transmission());
            verify(branches).findById(42L);
            verifyNoMoreInteractions(branches);
        } else {
            verifyNoInteractions(branches);
        }
        verify(reads).findByCode("XE-TEST01");
        verifyNoMoreInteractions(reads);
    }

    /** Xe thiếu trả rỗng; không tự tạo xe/chi nhánh hoặc đọc giá để thay báo giá đã chốt. */
    @Test
    void missingVehicleDoesNotQueryBranch() {
        var reads = mock(ReadVehiclePort.class);
        var branches = mock(BranchDirectory.class);
        assertTrue(new VehicleBookingQueryService(reads, branches).getActiveForBooking(GetVehicleQuery.from("XE-ABSENT")).isEmpty());
        verifyNoInteractions(branches);
    }

    /** ACTIVE thiếu thuộc tính là lỗi toàn vẹn, không giả thành không tìm thấy hoặc đoán số chỗ/hộp số. */
    @Test
    void corruptActiveDataIsNotSilentlyHidden() {
        var reads = mock(ReadVehiclePort.class);
        var branches = mock(BranchDirectory.class);
        var service = new VehicleBookingQueryService(reads, branches);
        for (var invalid : java.util.List.of(detail(VehicleStatus.ACTIVE, null, 7, Transmission.AUTOMATIC),
                detail(VehicleStatus.ACTIVE, 42L, null, Transmission.AUTOMATIC), detail(VehicleStatus.ACTIVE, 42L, 7, null))) {
            when(reads.findByCode("XE-TEST01")).thenReturn(Optional.of(invalid));
            assertThrows(IllegalStateException.class, () -> service.getActiveForBooking(GetVehicleQuery.from("XE-TEST01")));
        }
        verifyNoInteractions(branches);
    }

    /** Có ID liên kết nhưng mất chi nhánh phải báo lỗi, không trả branchCode null. */
    @Test
    void missingReferencedBranchIsIntegrityFailure() {
        var reads = mock(ReadVehiclePort.class);
        var branches = mock(BranchDirectory.class);
        when(reads.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(VehicleStatus.ACTIVE, 42L, 7, Transmission.MANUAL)));
        assertThrows(IllegalStateException.class, () -> new VehicleBookingQueryService(reads, branches)
                .getActiveForBooking(GetVehicleQuery.from("XE-TEST01")));
    }

    /** Lỗi nguồn dữ liệu truyền nguyên, không biến sự cố hạ tầng thành xe không tồn tại. */
    @Test
    void preservesStorageFailure() {
        var reads = mock(ReadVehiclePort.class);
        var failure = new IllegalStateException("Storage unavailable.");
        when(reads.findByCode("XE-TEST01")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                new VehicleBookingQueryService(reads, mock(BranchDirectory.class))
                        .getActiveForBooking(GetVehicleQuery.from("XE-TEST01"))));
    }

    /** Null query bị chặn trước đọc danh mục. */
    @Test
    void rejectsMissingQuery() {
        var reads = mock(ReadVehiclePort.class);
        var branches = mock(BranchDirectory.class);
        assertThrows(DomainException.class, () -> new VehicleBookingQueryService(reads, branches).getActiveForBooking(null));
        verifyNoInteractions(reads, branches);
    }

    /** Giá hiện tại cố ý khác báo giá; cổng đặt đơn không mang giá đó ra ngoài. */
    private static VehicleDetail detail(VehicleStatus status, Long branchId, Integer seats, Transmission transmission) {
        return new VehicleDetail(91L, "XE-TEST01", "BOOKING-TEST", OwnershipType.COMPANY, FuelType.PETROL,
                branchId, status, null, null, seats, transmission, "Toyota", "Vios", null, 2_000_000L);
    }
}
