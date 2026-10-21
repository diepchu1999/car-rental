package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-803/810: bảo vệ đọc chi tiết quản trị ngay tại use case, trước khi bổ sung mã chi nhánh. */
class VehicleReadAuthorizationTest {
    private static final GetVehicleQuery QUERY = GetVehicleQuery.from("XE-LOCAL1");
    private final ReadVehiclePort read = mock(ReadVehiclePort.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final VehicleQueryService service = new VehicleQueryService(read, branches, callers);

    /** ADMIN xem mọi chi nhánh dù chưa được gán hoặc đang gán sang chi nhánh khác. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanReadAcrossBranches(boolean assigned) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                assigned ? 2000L : null));
        assertAllowed(detail(1000L, VehicleStatus.DRAFT));
    }

    /** STAFF/MANAGER cùng CUSTOMER vẫn chỉ có phạm vi chi nhánh, so theo giá trị ID ngoài cache Long. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCanReadOwnBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role, "CUSTOMER"), 1000L));
        assertAllowed(detail(1000L, VehicleStatus.DRAFT));
    }

    /** Đọc không phải phê duyệt: mọi trạng thái và giấy tờ thiếu/hết hạn vẫn biểu diễn được. */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void authorizedReadDoesNotRevalidateApproval(VehicleStatus status) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of("BRANCH_STAFF"), 1000L));
        assertAllowed(detail(1000L, status));
    }

    /** Đổi mã sang xe khác chi nhánh bị chặn trước cả việc gọi branch để làm giàu view. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCannotReadAnotherBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 2000L));
        when(read.findByCode(QUERY.code())).thenReturn(Optional.of(detail(1000L, VehicleStatus.ACTIVE)));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verify(read).findByCode(QUERY.code());
        verifyNoMoreInteractions(read);
        verifyNoInteractions(branches);
    }

    /** Liên kết null không bao giờ được coi là chi nhánh của STAFF/MANAGER. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCannotReadVehicleWithoutMatchingBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(read.findByCode(QUERY.code())).thenReturn(Optional.of(detail(null, VehicleStatus.DRAFT)));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(branches);
    }

    /** ADMIN vẫn đọc được view không có liên kết, không bịa mã hoặc đọc branch bằng null. */
    @Test
    void adminCanReadViewWithoutBranchReference() {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN"), null));
        var raw = detail(null, VehicleStatus.DRAFT);
        when(read.findByCode(QUERY.code())).thenReturn(Optional.of(raw));
        assertEquals(raw, service.get(QUERY));
        verifyNoInteractions(branches);
    }

    /** STAFF/MANAGER chưa gán bị từ chối bằng lỗi riêng trước khi đọc xe. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotRead(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), null));
        assertForbidden(ErrorCode.STAFF_BRANCH_REQUIRED);
        verifyNoInteractions(read, branches);
    }

    /** Vai cấp công ty và các vai khác không được suy quyền đọc danh mục từ phạm vi hoặc CUSTOMER. */
    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER",
            "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotRead(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(read, branches);
    }

    /** Không có vai vẫn bị chặn dù có chi nhánh. */
    @Test
    void callerWithoutRolesCannotRead() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(read, branches);
    }

    /** Người đủ vai và phạm vi đã gán nhận đúng 404 khi mã xe không tồn tại. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void missingVehicleRemainsNotFound(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(read.findByCode(QUERY.code())).thenReturn(Optional.empty());
        var failure = assertThrowsExactly(DomainException.class, () -> service.get(QUERY));
        assertEquals(ErrorCode.VEHICLE_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
        verifyNoInteractions(branches);
    }

    /** Không có request hoặc danh tính không được thay bằng người gọi ADMIN mặc định. */
    @Test
    void callerFailuresStopBeforeReading() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class, () -> service.get(QUERY)));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class, () -> service.get(QUERY)));
        verifyNoInteractions(read, branches);
    }

    /** Kiểm đúng thứ tự người gọi → xe → chi nhánh và không đổi giá, giấy tờ, trạng thái. */
    private void assertAllowed(VehicleDetail raw) {
        when(read.findByCode(QUERY.code())).thenReturn(Optional.of(raw));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        assertEquals(raw.withBranchCode("CN-LOCAL1"), service.get(QUERY));
        var order = inOrder(callers, read, branches);
        order.verify(callers).current();
        order.verify(read).findByCode(QUERY.code());
        order.verify(branches).findById(1000L);
        verifyNoMoreInteractions(callers, read, branches);
    }

    /** Kiểm cả mã và nhóm lỗi để một lỗi khác không vô tình làm test xanh. */
    private void assertForbidden(ErrorCode expected) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.get(QUERY));
        assertEquals(expected, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }

    /** View có một ngày thiếu, một ngày đã hết hạn để chứng minh GET không kiểm lại cổng duyệt. */
    private VehicleDetail detail(Long branchId, VehicleStatus status) {
        return new VehicleDetail(5000L, QUERY.code(), "T08-READ-AUTH", OwnershipType.COMPANY,
                FuelType.PETROL, branchId, status, null, LocalDate.of(2020, 1, 1),
                5, Transmission.AUTOMATIC, "Toyota", "Vios", null, 855000L);
    }
}
