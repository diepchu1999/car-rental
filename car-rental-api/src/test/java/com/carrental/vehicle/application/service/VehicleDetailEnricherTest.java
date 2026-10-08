package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm BR-003/ADR-0008: trả mã chi nhánh qua directory, giữ nguyên lỗi và dữ liệu xe. */
class VehicleDetailEnricherTest {
    /** Mã không được suy từ ID, và view gốc do persistence trả về không bị thay đổi. */
    @Test
    void resolvesCodeWithoutChangingStoredView() {
        var branches = mock(BranchDirectory.class);
        when(branches.findById(42)).thenReturn(Optional.of(new BranchRef(42, "CN-ZYX987")));
        var raw = detail(42L);
        assertEquals(raw.withBranchCode("CN-ZYX987"), VehicleDetailEnricher.enrich(raw, branches));
        assertNull(raw.branchCode());
        verify(branches).findById(42);
        verifyNoMoreInteractions(branches);
    }

    /** Hồ sơ không có liên kết vẫn biểu diễn được mà không phân nhánh theo OwnershipType. */
    @Test
    void absentReferenceDoesNotCallBranch() {
        var branches = mock(BranchDirectory.class);
        assertEquals(detail(null), VehicleDetailEnricher.enrich(detail(null), branches));
        verifyNoInteractions(branches);
    }

    /** Có ID nhưng chi nhánh mất là lỗi toàn vẹn nội bộ, không giả mã null hoặc trả 404 cho mã xe. */
    @Test
    void queryRejectsDanglingReference() {
        var branches = mock(BranchDirectory.class);
        var read = mock(ReadVehiclePort.class);
        when(read.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(42L)));
        when(branches.findById(42)).thenReturn(Optional.empty());
        var service = new VehicleQueryService(read, branches);
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> service.get(GetVehicleQuery.from("XE-TEST01")));
        assertTrue(failure.getMessage().contains("missing branch"));
        verify(branches).findById(42);
        verifyNoMoreInteractions(branches);
    }

    /** Lỗi nguồn branch phải truyền ra nguyên trạng, không biến thành xe không tồn tại. */
    @Test
    void queryPropagatesBranchFailure() {
        var branches = mock(BranchDirectory.class);
        var read = mock(ReadVehiclePort.class);
        var failure = new IllegalStateException("Branch read failed.");
        when(read.findByCode("XE-TEST01")).thenReturn(Optional.of(detail(42L)));
        when(branches.findById(42)).thenThrow(failure);
        var service = new VehicleQueryService(read, branches);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class,
                () -> service.get(GetVehicleQuery.from("XE-TEST01"))));
        verify(branches).findById(42);
        verifyNoMoreInteractions(branches);
    }

    /** Tạo read view thô, không dựa vào một chi nhánh tồn tại thật. */
    private VehicleDetail detail(Long branchId) {
        return new VehicleDetail(100, "XE-TEST01", "TEST-PLATE", OwnershipType.COMPANY,
                FuelType.PETROL, branchId, VehicleStatus.DRAFT, null, null,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(), SPECIFICATIONS.make(), SPECIFICATIONS.model(), null);
    }
}
