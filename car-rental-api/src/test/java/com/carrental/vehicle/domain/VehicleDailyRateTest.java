package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.LocalDate;
import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm giá ngày gắn với vòng đời BR-010/234, không thêm luồng đổi trạng thái. */
class VehicleDailyRateTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    /** Cho tạo/gửi duyệt khi chưa có giá nhưng không cho ACTIVE; bổ sung giá rồi duyệt được. */
    @Test
    void requiresRateOnlyAtApproval() {
        var draft = vehicle(VehicleStatus.DRAFT, null);
        var pending = draft.submitForApproval();
        assertNull(pending.dailyRate());
        assertEquals(ErrorCode.VEHICLE_DAILY_RATE_REQUIRED,
                assertThrows(DomainException.class, () -> pending.approve(TODAY)).errorCode());
        var priced = pending.changeDailyRate(new DailyRate(855_000));
        assertEquals(new DailyRate(855_000), priced.approve(TODAY).dailyRate());
        assertNull(draft.dailyRate());
        assertNull(pending.dailyRate());
    }

    /** Đổi giá được ở cả sáu trạng thái, chỉ thay giá và không sửa bản cũ. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void changesOnlyRateInEveryStatus(VehicleStatus status) {
        var original = vehicle(status, new DailyRate(1_000_000));
        var changed = original.changeDailyRate(new DailyRate(855_000));
        assertEquals(new DailyRate(1_000_000), original.dailyRate());
        assertEquals(new DailyRate(855_000), changed.dailyRate());
        assertEquals(original.code(), changed.code());
        assertEquals(original.plateNumber(), changed.plateNumber());
        assertEquals(original.branchId(), changed.branchId());
        assertEquals(original.ownershipType(), changed.ownershipType());
        assertEquals(original.fuelType(), changed.fuelType());
        assertEquals(status, changed.status());
        assertSame(original.documents(), changed.documents());
        assertSame(original.specifications(), changed.specifications());
    }

    /** Khi nhiều điều kiện sai, kiểm trạng thái rồi giấy tờ trước lỗi thiếu giá như hợp đồng API. */
    @Test
    void checksStatusAndDocumentsBeforeMissingRate() {
        assertEquals(ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION,
                assertThrows(DomainException.class, () -> vehicle(VehicleStatus.DRAFT, null).approve(TODAY))
                        .errorCode());
        var missingDocuments = Vehicle.restore("XE-RATE01", "RATE-01", OwnershipType.COMPANY,
                FuelType.PETROL, 1L, VehicleStatus.PENDING_APPROVAL,
                new VehicleDocuments(null, null), SPECIFICATIONS, null);
        assertEquals(ErrorCode.VEHICLE_DOCUMENT_MISSING,
                assertThrows(DomainException.class, () -> missingDocuments.approve(TODAY)).errorCode());
        var expiredDocuments = Vehicle.restore("XE-RATE01", "RATE-01", OwnershipType.COMPANY,
                FuelType.PETROL, 1L, VehicleStatus.PENDING_APPROVAL,
                new VehicleDocuments(TODAY, TODAY), SPECIFICATIONS, null);
        assertEquals(ErrorCode.VEHICLE_DOCUMENT_EXPIRED,
                assertThrows(DomainException.class, () -> expiredDocuments.approve(TODAY)).errorCode());
    }

    /** Domain không cho lách đường đổi giá thành xóa giá, kể cả xe DRAFT. */
    @Test
    void cannotClearRate() {
        assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE, assertThrows(DomainException.class,
                () -> vehicle(VehicleStatus.DRAFT, new DailyRate(1000)).changeDailyRate(null)).errorCode());
    }

    /** Dữ liệu xe đã từng duyệt không được khôi phục thành aggregate thiếu giá. */
    @ParameterizedTest
    @EnumSource(value = VehicleStatus.class, names = {"ACTIVE", "INACTIVE", "RETIRED"})
    void rejectsMissingRateForPreviouslyApprovedStates(VehicleStatus status) {
        assertEquals(ErrorCode.VEHICLE_DAILY_RATE_REQUIRED,
                assertThrows(DomainException.class, () -> vehicle(status, null)).errorCode());
    }

    /** Tạo hồ sơ có giấy tờ hợp lệ để lỗi giá không bị lỗi giấy tờ che khuất. */
    private static Vehicle vehicle(VehicleStatus status, DailyRate rate) {
        return Vehicle.restore("XE-RATE01", "RATE-01", OwnershipType.COMPANY, FuelType.PETROL,
                1L, status, new VehicleDocuments(TODAY.plusDays(1), TODAY.plusDays(2)), SPECIFICATIONS, rate);
    }
}
