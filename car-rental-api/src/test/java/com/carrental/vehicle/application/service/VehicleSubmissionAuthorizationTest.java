package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleSpecifications;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-810: quyền gửi duyệt theo xe đã lưu, không lẫn với quyền nhập giá hoặc phê duyệt. */
class VehicleSubmissionAuthorizationTest {
    private static final String CODE = "XE-LOCAL1";
    private static final SubmitVehicleForApprovalCommand COMMAND = SubmitVehicleForApprovalCommand.from(CODE);
    private final WriteVehiclePort write = mock(WriteVehiclePort.class);
    private final ReadVehiclePort read = mock(ReadVehiclePort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final VehicleCommandService service = new VehicleCommandService(
            write, read, codes, branches, Clock.systemUTC(), callers);

    /** ADMIN có hoặc không được gán chi nhánh vẫn gửi được xe ở chi nhánh khác. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanSubmitAcrossBranches(boolean assignedElsewhere) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                assignedElsewhere ? 2000L : null));
        assertSuccessfulSubmission();
    }

    /** STAFF/MANAGER có thêm CUSTOMER vẫn gửi được xe cùng chi nhánh dù thiếu giấy tờ và giá. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCanSubmitOwnBranchVehicle(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role, "CUSTOMER"), 1000L));
        assertSuccessfulSubmission();
    }

    /** Đổi mã xe không vượt phạm vi; chặn quyền trước kiểm trạng thái nên không lộ trạng thái xe khác. */
    @ParameterizedTest
    @CsvSource({"BRANCH_STAFF,DRAFT", "BRANCH_MANAGER,DRAFT",
            "BRANCH_STAFF,PENDING_APPROVAL", "BRANCH_MANAGER,PENDING_APPROVAL"})
    void employeeCannotSubmitAnotherBranchVehicle(String role, VehicleStatus status) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 2000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(status)));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches, codes);
    }

    /** Nhân viên chưa gán bị chặn trước tải xe; không lấy chi nhánh của xe làm phạm vi mặc định. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotSubmit(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), null));
        assertFailure(ErrorCode.STAFF_BRANCH_REQUIRED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Vai khác không tự có quyền gửi duyệt, kể cả vai cấp công ty hoặc có cùng branchId. */
    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER",
            "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotSubmit(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Không có vai không được gửi duyệt dù có chi nhánh. */
    @Test
    void callerWithoutRolesCannotSubmit() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Chưa xác thực hoặc gọi ngoài HTTP truyền nguyên lỗi, không truy cập dữ liệu xe. */
    @Test
    void callerFailuresStopSubmission() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class,
                () -> service.submitForApproval(COMMAND)));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class,
                () -> service.submitForApproval(COMMAND)));
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Đủ vai/phạm vi nhưng mã không tồn tại giữ đúng VEHICLE_NOT_FOUND. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void missingVehicleRemainsNotFound(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.empty());
        assertFailure(ErrorCode.VEHICLE_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verifyNoInteractions(write, branches, codes);
    }

    /** Đúng quyền nhưng đã gửi duyệt thì domain vẫn chặn lần hai bằng 422, không UPDATE. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void allowedCallerCannotResubmitPendingVehicle(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.PENDING_APPROVAL)));
        assertFailure(ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION, DomainException.Category.RULE_VIOLATION);
        verifyNoInteractions(write, branches, codes);
    }

    /** UPDATE thua tranh chấp vẫn là 409; không đọc lại, không tự thử lại hoặc coi là thành công. */
    @Test
    void allowedSubmissionPreservesOptimisticConflict() {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of("BRANCH_STAFF"), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.DRAFT)));
        when(write.updateStatus(CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL)).thenReturn(false);
        assertFailure(ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION, DomainException.Category.CONFLICT);
        verify(write).updateStatus(CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(write, read);
        verifyNoInteractions(branches, codes);
    }

    /** Aggregate hợp lệ cho bước gửi duyệt; không yêu cầu giấy tờ/giá của bước phê duyệt. */
    private Vehicle vehicle(VehicleStatus status) {
        return Vehicle.restore(CODE, "T08-SUBMIT-AUTH", OwnershipType.COMPANY, FuelType.PETROL, 1000L,
                status, new VehicleDocuments(null, null),
                new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"), null);
    }

    /** Kiểm thứ tự quyền → tải xe → UPDATE có trạng thái cũ → đọc lại → bổ sung mã chi nhánh. */
    private void assertSuccessfulSubmission() {
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.DRAFT)));
        when(write.updateStatus(CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL)).thenReturn(true);
        var detail = new VehicleDetail(77L, CODE, "T08-SUBMIT-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.PENDING_APPROVAL, null, null, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, null);
        when(read.findByCode(CODE)).thenReturn(Optional.of(detail));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        assertEquals(detail.withBranchCode("CN-LOCAL1"), service.submitForApproval(COMMAND));
        var order = inOrder(callers, read, write, branches);
        order.verify(callers).current();
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL);
        order.verify(read).findByCode(CODE);
        order.verify(branches).findById(1000L);
        verifyNoMoreInteractions(callers, read, write, branches);
        verifyNoInteractions(codes);
    }

    /** Kiểm đúng loại và mã lỗi; exception bất kỳ không đủ chứng minh quyền hay trạng thái. */
    private void assertFailure(ErrorCode code, DomainException.Category category) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.submitForApproval(COMMAND));
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
