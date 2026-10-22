package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-810: chỉ ADMIN/MANAGER đúng chi nhánh được duyệt; giữ cổng giấy tờ và giá BR-005/234. */
class VehicleApprovalAuthorizationTest {
    private static final String CODE = "XE-LOCAL1";
    private static final ApproveVehicleCommand COMMAND = ApproveVehicleCommand.from(CODE);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T18:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final DailyRate RATE = new DailyRate(855000);
    private final WriteVehiclePort write = mock(WriteVehiclePort.class);
    private final ReadVehiclePort read = mock(ReadVehiclePort.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final VehicleCommandService service = new VehicleCommandService(write, read, codes, branches, CLOCK, callers);

    /** ADMIN không cần gán chi nhánh và không bị giới hạn bởi chi nhánh được gán khác. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanApproveAcrossBranches(boolean assignedElsewhere) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                assignedElsewhere ? 2000L : null));
        assertSuccessfulApproval();
    }

    /** MANAGER cùng chi nhánh được duyệt, kể cả có thêm vai CUSTOMER theo BR-809. */
    @Test
    void managerCanApproveOwnBranchVehicle() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER", "CUSTOMER"), 1000L));
        assertSuccessfulApproval();
    }

    /** STAFF và mọi vai khác bị chặn ngay, dù cùng chi nhánh; gửi duyệt không đồng nghĩa được duyệt. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT",
            "OPERATIONS_MANAGER", "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotApproveEvenInOwnBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** MANAGER khác chi nhánh nhận 403 trước kiểm trạng thái, không lộ DRAFT/PENDING/ACTIVE. */
    @ParameterizedTest
    @EnumSource(value = VehicleStatus.class, names = {"DRAFT", "PENDING_APPROVAL", "ACTIVE"})
    void managerCannotApproveAnotherBranchVehicle(VehicleStatus state) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 2000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(state, TODAY.plusDays(1), RATE)));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches, codes);
    }

    /** Xe chi nhánh khác thiếu cả giấy tờ/giá vẫn trả 403, không trả lỗi nội dung hồ sơ. */
    @Test
    void managerScopeIsCheckedBeforeDocumentsAndRate() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 2000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.PENDING_APPROVAL, null, null)));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(write, branches, codes);
    }

    /** MANAGER chưa gán chi nhánh nhận lỗi riêng trước tải xe. */
    @Test
    void unassignedManagerCannotApprove() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), null));
        assertFailure(ErrorCode.STAFF_BRANCH_REQUIRED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Danh tính không có vai không được cấp quyền mặc định. */
    @Test
    void callerWithoutRolesCannotApprove() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN);
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Chưa xác thực/gọi ngoài HTTP truyền nguyên lỗi, không truy cập dữ liệu xe. */
    @Test
    void callerFailuresStopApproval() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class, () -> service.approve(COMMAND)));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class, () -> service.approve(COMMAND)));
        verifyNoInteractions(read, write, branches, codes);
    }

    /** Người đủ quyền gọi mã không tồn tại vẫn nhận VEHICLE_NOT_FOUND, không ghi dữ liệu. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void missingVehicleRemainsNotFound(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of(role), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.empty());
        assertFailure(ErrorCode.VEHICLE_NOT_FOUND, DomainException.Category.NOT_FOUND);
        verifyNoInteractions(write, branches, codes);
    }

    /** Đúng quyền không bỏ qua trạng thái/giấy tờ/giá; hạn ngày VN hôm nay vẫn hết hạn. */
    @ParameterizedTest
    @CsvSource({
            "ADMIN,VEHICLE_INVALID_STATUS_TRANSITION", "BRANCH_MANAGER,VEHICLE_INVALID_STATUS_TRANSITION",
            "ADMIN,VEHICLE_DOCUMENT_MISSING", "BRANCH_MANAGER,VEHICLE_DOCUMENT_MISSING",
            "ADMIN,VEHICLE_DOCUMENT_EXPIRED", "BRANCH_MANAGER,VEHICLE_DOCUMENT_EXPIRED",
            "ADMIN,VEHICLE_DAILY_RATE_REQUIRED", "BRANCH_MANAGER,VEHICLE_DAILY_RATE_REQUIRED"})
    void authorizedCallerStillMustMeetApprovalRequirements(String role, ErrorCode expected) {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of(role), 1000L));
        Vehicle invalid = switch (expected) {
            case VEHICLE_INVALID_STATUS_TRANSITION -> vehicle(VehicleStatus.DRAFT, TODAY.plusDays(1), RATE);
            case VEHICLE_DOCUMENT_MISSING -> vehicle(VehicleStatus.PENDING_APPROVAL, null, RATE);
            case VEHICLE_DOCUMENT_EXPIRED -> vehicle(VehicleStatus.PENDING_APPROVAL, TODAY, RATE);
            case VEHICLE_DAILY_RATE_REQUIRED -> vehicle(VehicleStatus.PENDING_APPROVAL, TODAY.plusDays(1), null);
            default -> throw new AssertionError("Unexpected approval fixture error: " + expected);
        };
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(invalid));
        assertFailure(expected, DomainException.Category.RULE_VIOLATION);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches, codes);
    }

    /** UPDATE thua tranh chấp vẫn là 409, không đọc lại hay tự thử lại phê duyệt. */
    @Test
    void allowedApprovalPreservesOptimisticConflict() {
        when(callers.current()).thenReturn(new CallerRef("TK-MANAG1", Set.of("BRANCH_MANAGER"), 1000L));
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.PENDING_APPROVAL, TODAY.plusDays(1), RATE)));
        when(write.updateStatus(CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE)).thenReturn(false);
        assertFailure(ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION, DomainException.Category.CONFLICT);
        verify(write).updateStatus(CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE);
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(write, read);
        verifyNoInteractions(branches, codes);
    }

    /** Khôi phục hồ sơ theo kịch bản; hạn giấy tờ được kiểm khi duyệt chứ không lúc tải. */
    private Vehicle vehicle(VehicleStatus status, LocalDate expiresOn, DailyRate rate) {
        return Vehicle.restore(CODE, "T08-APPROVE-AUTH", OwnershipType.COMPANY, FuelType.PETROL, 1000L,
                status, new VehicleDocuments(expiresOn, expiresOn),
                new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"), rate);
    }

    /** Kiểm toàn bộ thứ tự quyền → tải → UPDATE có điều kiện → reload → mã chi nhánh. */
    private void assertSuccessfulApproval() {
        LocalDate expiry = TODAY.plusDays(1);
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(vehicle(VehicleStatus.PENDING_APPROVAL, expiry, RATE)));
        when(write.updateStatus(CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE)).thenReturn(true);
        var detail = new VehicleDetail(77L, CODE, "T08-APPROVE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.ACTIVE, expiry, expiry, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, RATE.amount());
        when(read.findByCode(CODE)).thenReturn(Optional.of(detail));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        assertEquals(detail.withBranchCode("CN-LOCAL1"), service.approve(COMMAND));
        var order = inOrder(callers, read, write, branches);
        order.verify(callers).current();
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE);
        order.verify(read).findByCode(CODE);
        order.verify(branches).findById(1000L);
        verifyNoMoreInteractions(callers, read, write, branches);
        verifyNoInteractions(codes);
    }

    /** Kiểm đúng loại và mã lỗi, không coi exception bất kỳ là chứng minh từ chối quyền. */
    private void assertFailure(ErrorCode code, DomainException.Category category) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.approve(COMMAND));
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
