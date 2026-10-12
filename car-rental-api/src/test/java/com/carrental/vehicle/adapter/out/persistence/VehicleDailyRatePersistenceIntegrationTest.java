package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm adapter giá BR-234 trên PostgreSQL thật; mỗi test rollback và không đụng CSDL local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleDailyRatePersistenceIntegrationTest {
    @Autowired private ReadVehiclePort reads;
    @Autowired private WriteVehiclePort writes;

    /** UPDATE giá chỉ tác động xe đã chọn ở mọi trạng thái, không ghi đè trường nào khác. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void changesOnlySelectedVehicleRate(VehicleStatus status) {
        assertTrue(writes.insert(vehicle("XE-RATE01", status, new DailyRate(1_000_000))));
        assertTrue(writes.insert(vehicle("XE-RATE02", VehicleStatus.DRAFT, null)));
        var before = reads.findByCode("XE-RATE01").orElseThrow();
        var other = reads.findByCode("XE-RATE02").orElseThrow();
        assertTrue(writes.updateDailyRate(before.code(), new DailyRate(855_000)));
        var after = reads.findByCode(before.code()).orElseThrow();
        assertEquals(new VehicleDetail(before.id(), before.code(), before.plateNumber(), before.ownershipType(),
                before.fuelType(), before.branchId(), before.status(), before.inspectionExpiresOn(),
                before.liabilityInsuranceExpiresOn(), before.seats(), before.transmission(), before.make(),
                before.model(), before.branchCode(), 855_000L), after);
        assertEquals(new DailyRate(855_000), reads.loadAggregate(before.code()).orElseThrow().dailyRate());
        assertEquals(other, reads.findByCode("XE-RATE02").orElseThrow());
        assertNull(reads.loadAggregate("XE-RATE02").orElseThrow().dailyRate());
        assertTrue(writes.updateDailyRate(before.code(), new DailyRate(855_000)));
        assertEquals(after, reads.findByCode(before.code()).orElseThrow());
    }

    /** Mã không tồn tại trả false, không tự tạo xe. */
    @Test
    void returnsFalseForMissingVehicle() {
        assertFalse(writes.updateDailyRate("XE-MISS01", new DailyRate(1000)));
        assertTrue(reads.findByCode("XE-MISS01").isEmpty());
    }

    /** Hồ sơ cũ có thể thiếu giấy tờ; test chỉ kiểm thao tác đổi giá, không duyệt lại. */
    private static Vehicle vehicle(String code, VehicleStatus status, DailyRate rate) {
        return Vehicle.restore(code, code, OwnershipType.COMPANY, FuelType.PETROL, 42L,
                status, new VehicleDocuments(null, null), SPECIFICATIONS, rate);
    }
}
