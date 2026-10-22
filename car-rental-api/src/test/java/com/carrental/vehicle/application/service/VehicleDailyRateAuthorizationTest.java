package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.ChangeVehicleDailyRateCommand;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.DailyRate;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleSpecifications;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-234/810: kiểm quyền đổi giá ở use case, không làm yếu điều kiện giá hay cơ chế cập nhật hiện có. */
class VehicleDailyRateAuthorizationTest {
    private static final String CODE = "XE-LOCAL1";
    private static final DailyRate RATE = new DailyRate(855000);
    private static final ChangeVehicleDailyRateCommand COMMAND = new ChangeVehicleDailyRateCommand(CODE, RATE);
    private final ReadVehiclePort read = mock(ReadVehiclePort.class);
    private final WriteVehiclePort write = mock(WriteVehiclePort.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final VehicleCommandService service = new VehicleCommandService(
            write, read, codes, branches, Clock.systemUTC(), callers);

    /** ADMIN không cần gán chi nhánh, cũng không bị giới hạn bởi một phân công khác. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanChangeRateAcrossBranches(boolean assigned) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                assigned ? 2000L : null));
        assertSuccessfulChange(vehicle(VehicleStatus.DRAFT, null));
    }

    /** MANAGER cùng chi nhánh đổi giá ở mọi trạng thái; giấy tờ thiếu không phải điều kiện đổi giá. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void managerCanChangeRateInEveryState(VehicleStatus state) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER", "CUSTOMER"), 1000L));
        assertSuccessfulChange(vehicle(state, new DailyRate(1000000)));
    }

    /** Gửi lại đúng giá hiện tại không gây lỗi hoặc chuyển trạng thái xe. */
    @Test
    void managerCanRepeatSameRate() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 1000L));
        assertSuccessfulChange(vehicle(VehicleStatus.ACTIVE, RATE));
    }

    /** STAFF và các vai khác không được ghi giá dù cùng chi nhánh; bị chặn trước đọc xe. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT",
            "OPERATIONS_MANAGER", "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotChangeRate(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** MANAGER đổi mã xe sang chi nhánh khác bị chặn trước ghi hoặc làm giàu view. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void managerCannotChangeAnotherBranchVehicleRate(VehicleStatus state) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 2000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(state, RATE)));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches, codes);
    }

    /** MANAGER chưa gán nhận đúng lỗi riêng trước khi tải xe. */
    @Test
    void unassignedManagerCannotChangeRate() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), null));
        assertFailure(ErrorCode.STAFF_BRANCH_REQUIRED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Không có vai không được suy quyền từ việc có chi nhánh. */
    @Test
    void callerWithoutRolesCannotChangeRate() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Chưa xác thực hoặc gọi ngoài HTTP truyền nguyên lỗi, không tự tạo người gọi mặc định. */
    @Test
    void callerFailuresStopBeforeReading() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class, () -> service.changeDailyRate(COMMAND)));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class, () -> service.changeDailyRate(COMMAND)));
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Người đủ quyền gọi mã không tồn tại nhận đúng 404 và không ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void missingVehicleRemainsNotFound(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of(role), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.empty());
        assertFailure(ErrorCode.VEHICLE_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verifyNoInteractions(write, branches, codes);
    }

    /** UPDATE không tìm thấy dòng giữ lỗi 404, không tự thử lại hoặc đọc view sau thất bại. */
    @Test
    void missingVehicleDuringUpdateStopsWithoutReload() {
        stubAuthorizedUpdate();
        when(write.updateDailyRate(CODE, RATE)).thenReturn(false);
        assertFailure(ErrorCode.VEHICLE_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verify(read).loadAggregate(CODE);
        verify(write).updateDailyRate(CODE, RATE);
        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches, codes);
    }

    /** Lỗi ghi truyền nguyên trạng, không giả thành lỗi quyền hay thử lại. */
    @Test
    void storageFailureIsNotMaskedOrRetried() {
        stubAuthorizedUpdate();
        var failure = new IllegalStateException("Rate storage failed.");
        when(write.updateDailyRate(CODE, RATE)).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service.changeDailyRate(COMMAND)));
        verify(write).updateDailyRate(CODE, RATE);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches, codes);
    }

    /** Không reload được sau UPDATE vẫn là lỗi runtime để transaction rollback, không báo thành công. */
    @Test
    void missingReloadStillThrowsInternalFailure() {
        stubAuthorizedUpdate();
        when(write.updateDailyRate(CODE, RATE)).thenReturn(true);
        when(read.findByCode(CODE)).thenReturn(Optional.empty());
        var failure = assertThrowsExactly(IllegalStateException.class, () -> service.changeDailyRate(COMMAND));
        assertEquals("Updated vehicle could not be reloaded: " + CODE, failure.getMessage());
        verifyNoInteractions(branches, codes);
    }

    /** Chuẩn bị đúng quyền và aggregate, để lỗi sau đó chỉ xuất phát từ cổng ghi/reload. */
    private void stubAuthorizedUpdate() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.DRAFT, null)));
    }

    /** Hồ sơ đủ tính nhất quán để khôi phục nhưng cố ý thiếu giấy tờ, không cần phê duyệt lại. */
    private Vehicle vehicle(VehicleStatus state, DailyRate rate) {
        return Vehicle.restore(CODE, "T08-RATE-AUTH", OwnershipType.COMPANY, FuelType.PETROL, 1000L,
                state, new VehicleDocuments(null, null),
                new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"), rate);
    }

    /** Kiểm thứ tự quyền → aggregate → ghi duy nhất giá → reload → mã chi nhánh. */
    private void assertSuccessfulChange(Vehicle original) {
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(original));
        when(write.updateDailyRate(CODE, RATE)).thenReturn(true);
        var detail = new VehicleDetail(77L, CODE, original.plateNumber(), original.ownershipType(), original.fuelType(),
                original.branchId(), original.status(), null, null, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, RATE.amount());
        when(read.findByCode(CODE)).thenReturn(Optional.of(detail));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        assertEquals(detail.withBranchCode("CN-LOCAL1"), service.changeDailyRate(COMMAND));
        var order = inOrder(callers, read, write, branches);
        order.verify(callers).current();
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateDailyRate(CODE, RATE);
        order.verify(read).findByCode(CODE);
        order.verify(branches).findById(1000L);
        verifyNoMoreInteractions(callers, read, write, branches);
        verifyNoInteractions(codes);
    }

    /** Đối chiếu cả mã và nhóm lỗi, không coi exception bất kỳ là bằng chứng bảo vệ. */
    private void assertFailure(ErrorCode code, DomainException.Category category) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.changeDailyRate(COMMAND));
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
