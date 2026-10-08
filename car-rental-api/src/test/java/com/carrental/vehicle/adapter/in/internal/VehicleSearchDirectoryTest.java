package com.carrental.vehicle.adapter.in.internal;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.api.VehicleSearchView;
import com.carrental.vehicle.application.view.VehicleSearchSummary;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm lối vào cross-module validate rồi map, không trả view nội bộ của vehicle. */
class VehicleSearchDirectoryTest {
    /** Mọi tham số được chuyển đúng tới use case và mọi trường được map đúng ra API. */
    @Test
    void validatesDelegatesAndMaps() {
        var directory = new VehicleSearchDirectoryAdapter(query -> {
            assertEquals(List.of(42L), query.branchIds());
            assertEquals(RentalType.DAILY, query.rentalType());
            assertEquals(Integer.valueOf(7), query.seats());
            assertEquals("MANUAL", query.transmission().name());
            assertEquals("DIESEL", query.fuelType().name());
            assertEquals("Ford", query.make());
            assertEquals("Everest", query.model());
            assertEquals(Boolean.TRUE, query.collateralFree());
            return List.of(new VehicleSearchSummary(7, "XE-DIR001", 42,
                    7, "MANUAL", "DIESEL", "Ford", "Everest", true));
        });
        var result = directory.list(List.of(42L), RentalType.DAILY,
                7, "MANUAL", "DIESEL", " Ford ", " Everest ", true);
        assertEquals(List.of(new VehicleSearchView(7, "XE-DIR001", 42,
                7, "MANUAL", "DIESEL", "Ford", "Everest", true)), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Enum sai bị chặn trước khi gọi use case, không tự bỏ bộ lọc. */
    @Test
    void rejectsBadFilterBeforeDelegation() {
        var directory = new VehicleSearchDirectoryAdapter(query -> {
            throw new AssertionError("Invalid input must not reach the use case.");
        });
        var error = assertThrowsExactly(DomainException.class, () -> directory.list(List.of(42L),
                RentalType.DAILY, null, "CVT", null, null, null, null));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
    }

    /** Hợp đồng chỉ chứa kiểu Java/API/từ vựng chung, không làm lộ domain hay loại sở hữu. */
    @Test
    void publicContractDoesNotExposeVehicleDomain() {
        for (var component : VehicleSearchView.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("ownership"));
            assertFalse(component.getType().getName().startsWith("com.carrental.vehicle.domain."));
        }
        for (var method : VehicleSearchDirectory.class.getDeclaredMethods()) {
            assertFalse(method.getGenericReturnType().getTypeName().contains("VehicleRef"));
            for (var type : method.getParameterTypes()) {
                assertFalse(type.getName().startsWith("com.carrental.vehicle.domain."));
            }
        }
    }
}
