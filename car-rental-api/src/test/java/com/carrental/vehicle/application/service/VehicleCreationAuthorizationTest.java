package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-803/810: use case tự chặn tạo xe sai vai, sai chi nhánh và STAFF nhập giá. */
class VehicleCreationAuthorizationTest {
    private static final BranchRef BRANCH = new BranchRef(1000L, "CN-LOCAL1");
    private final WriteVehiclePort write = mock(WriteVehiclePort.class);
    private final ReadVehiclePort read = mock(ReadVehiclePort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final VehicleCommandService service = new VehicleCommandService(
            write, read, codes, branches, Clock.systemUTC(), callers);

    /** ADMIN được tạo có/không giá dù chưa gán hoặc được gán chi nhánh khác. */
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void adminCanCreateInAnyBranch(boolean assignedElsewhere, boolean priced) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                assignedElsewhere ? 2000L : null));
        assertSuccessfulCreation(command(priced));
    }

    /** STAFF tạo không giá; MANAGER tạo có hoặc không giá ở đúng chi nhánh, cùng vai CUSTOMER. */
    @ParameterizedTest
    @CsvSource({"BRANCH_STAFF,false", "BRANCH_MANAGER,false", "BRANCH_MANAGER,true"})
    void employeeCanCreateInOwnBranch(String role, boolean priced) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role, "CUSTOMER"), 1000L));
        assertSuccessfulCreation(command(priced));
    }

    /** STAFF gửi giá hợp lệ vẫn nhận ACCESS_DENIED trước mọi tra cứu, sinh mã hay ghi. */
    @ParameterizedTest
    @ValueSource(longs = {1000, 855000, 1000000})
    void staffCannotSupplyDailyRate(long rate) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of("BRANCH_STAFF"), 1000L));
        CreateVehicleCommand input = command(false);
        input = new CreateVehicleCommand(input.plateNumber(), input.ownershipType(), input.fuelType(),
                input.branchCode(), input.documents(), input.specifications(),
                new com.carrental.vehicle.domain.DailyRate(rate));
        assertForbidden(input, ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(branches, codes, write, read);
    }

    /** Thay mã chi nhánh trong command không cho STAFF/MANAGER tạo xe ngoài phạm vi. */
    @ParameterizedTest
    @CsvSource({"BRANCH_STAFF,false", "BRANCH_MANAGER,false", "BRANCH_MANAGER,true"})
    void employeeCannotCreateInAnotherBranch(String role, boolean priced) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 2000L));
        when(branches.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        assertForbidden(command(priced), ErrorCode.ACCESS_DENIED);
        verify(branches).findByCode(BRANCH.code());
        verifyNoMoreInteractions(branches);
        verifyNoInteractions(codes, write, read);
    }

    /** STAFF/MANAGER chưa gán chi nhánh bị chặn trước tra mã, không đoán chi nhánh từ command. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotCreate(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), null));
        assertForbidden(command(false), ErrorCode.STAFF_BRANCH_REQUIRED);
        verifyNoInteractions(branches, codes, write, read);
    }

    /** Các vai khác không tự có quyền tạo xe kể cả khi có branchId trùng đích. */
    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER",
            "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotCreate(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertForbidden(command(false), ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(branches, codes, write, read);
    }

    /** Danh tính không có vai bị từ chối, không cấp quyền mặc định. */
    @Test
    void callerWithoutRolesCannotCreate() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertForbidden(command(false), ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(branches, codes, write, read);
    }

    /** Lỗi thiếu xác thực hoặc gọi ngoài HTTP truyền nguyên và không thực hiện I/O nghiệp vụ. */
    @Test
    void callerFailuresStopCreation() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class, () -> service.create(command(false))));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class, () -> service.create(command(false))));
        verifyNoInteractions(branches, codes, write, read);
    }

    /** Người đủ vai/phạm vi nhưng mã chi nhánh không tồn tại nhận đúng lỗi BR-003, không sinh xe. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void missingBranchStopsCreation(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(branches.findByCode(BRANCH.code())).thenReturn(Optional.empty());
        var failure = assertThrowsExactly(DomainException.class, () -> service.create(command(false)));
        assertEquals(ErrorCode.BRANCH_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verifyNoInteractions(codes, write, read);
    }

    /** Dựng command hợp lệ để lỗi quyền không bị lẫn với lỗi đầu vào BR-018/234. */
    private CreateVehicleCommand command(boolean priced) {
        return CreateVehicleCommand.from("T08-VEHICLE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                BRANCH.code(), null, null, 5, Transmission.AUTOMATIC, "Toyota", "Vios",
                priced ? BigDecimal.valueOf(855000) : null);
    }

    /** Kiểm thứ tự kiểm quyền/tra cứu/ghi và giá được giữ đúng, không âm thầm bỏ giá. */
    private void assertSuccessfulCreation(CreateVehicleCommand input) {
        when(branches.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        when(codes.generate("XE")).thenReturn("XE-LOCAL1");
        when(write.insert(any())).thenReturn(true);
        Long rate = input.dailyRate() == null ? null : input.dailyRate().amount();
        var detail = new VehicleDetail(77L, "XE-LOCAL1", input.plateNumber(), OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH.id(), VehicleStatus.DRAFT, null, null, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, rate);
        when(read.findByCode("XE-LOCAL1")).thenReturn(Optional.of(detail));
        assertEquals(detail.withBranchCode(BRANCH.code()), service.create(input));
        var vehicle = ArgumentCaptor.forClass(Vehicle.class);
        var order = inOrder(callers, branches, codes, write, read);
        order.verify(callers).current();
        order.verify(branches).findByCode(BRANCH.code());
        order.verify(codes).generate("XE");
        order.verify(write).insert(vehicle.capture());
        order.verify(read).findByCode("XE-LOCAL1");
        assertEquals(BRANCH.id(), vehicle.getValue().branchId());
        assertEquals(VehicleStatus.DRAFT, vehicle.getValue().status());
        assertEquals(input.dailyRate(), vehicle.getValue().dailyRate());
        verifyNoMoreInteractions(callers, branches, codes, write, read);
    }

    /** Kiểm đúng mã và loại lỗi quyền, không coi exception bất kỳ là test âm thành công. */
    private void assertForbidden(CreateVehicleCommand input, ErrorCode expected) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.create(input));
        assertEquals(expected, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }
}
