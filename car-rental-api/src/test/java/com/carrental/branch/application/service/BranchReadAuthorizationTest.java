package com.carrental.branch.application.service;

import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.FindBranchByIdQuery;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-803/810: kiểm quyền đọc ngay tại use case; giữ đường tra cứu nội bộ không cần HTTP. */
class BranchReadAuthorizationTest {
    private static final BranchDetail BRANCH = new BranchDetail(
            1000L, "CN-LOCAL1", 10.76, 106.66, "Test Branch", "Local test address");
    private static final GetBranchQuery QUERY = GetBranchQuery.from(BRANCH.code());
    private final ReadBranchPort read = mock(ReadBranchPort.class);
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final BranchQueryService service = new BranchQueryService(read, callers);

    /** ADMIN không cần chi nhánh và không bị giới hạn bởi một chi nhánh được gán. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminCanReadAnyBranch(boolean hasAssignment) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADMIN1", Set.of("ADMIN", "CUSTOMER"),
                hasAssignment ? 2000L : null));
        when(read.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        assertSame(BRANCH, service.get(QUERY));
        verify(read).findByCode(BRANCH.code());
        verifyNoMoreInteractions(read);
    }

    /** STAFF/MANAGER được xem đúng chi nhánh, kể cả giữ thêm vai CUSTOMER (BR-809). */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCanReadOwnBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role, "CUSTOMER"), 1000L));
        when(read.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        assertSame(BRANCH, service.get(QUERY));
        var order = inOrder(callers, read);
        order.verify(callers).current();
        order.verify(read).findByCode(BRANCH.code());
        verifyNoMoreInteractions(callers, read);
    }

    /** Đổi mã URL sang chi nhánh tồn tại khác phải bị từ chối, không trả view của chi nhánh đó. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCannotReadAnotherBranch(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 2000L));
        when(read.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verify(read).findByCode(BRANCH.code());
        verifyNoMoreInteractions(read);
    }

    /** Vai cấp chi nhánh chưa được gán nhận đúng lỗi riêng trước khi đọc dữ liệu. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotRead(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), null));
        assertForbidden(ErrorCode.STAFF_BRANCH_REQUIRED);
        verifyNoInteractions(read);
    }

    /** Vai cấp công ty và các vai còn lại không tự có quyền xem danh mục quản trị (BR-810). */
    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER",
            "HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void otherRolesCannotReadEvenWithBranchAssignment(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(role), 1000L));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(read);
    }

    /** Không có vai phải bị chặn, không suy quyền từ việc có branchId. */
    @Test
    void callerWithoutRolesCannotRead() {
        when(callers.current()).thenReturn(new CallerRef("TK-OTHER1", Set.of(), 1000L));
        assertForbidden(ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(read);
    }

    /** Người có quyền đọc nhận đúng lỗi không tìm thấy; không giả thành lỗi quyền hoặc thành công. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void missingBranchRemainsNotFound(String role) {
        when(callers.current()).thenReturn(new CallerRef("TK-STAFF1", Set.of(role), 1000L));
        when(read.findByCode(BRANCH.code())).thenReturn(Optional.empty());
        var failure = assertThrowsExactly(DomainException.class, () -> service.get(QUERY));
        assertEquals(ErrorCode.BRANCH_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** Chưa xác thực hoặc gọi get ngoài HTTP không được âm thầm cấp quyền hay đọc dữ liệu. */
    @Test
    void callerFailuresPropagateBeforeReading() {
        var unauthenticated = DomainException.unauthenticated();
        when(callers.current()).thenThrow(unauthenticated);
        assertSame(unauthenticated, assertThrowsExactly(DomainException.class, () -> service.get(QUERY)));
        var outsideRequest = new IllegalStateException("CallerDirectory requires an active HTTP request.");
        doThrow(outsideRequest).when(callers).current();
        assertSame(outsideRequest, assertThrowsExactly(IllegalStateException.class, () -> service.get(QUERY)));
        verifyNoInteractions(read);
    }

    /** Ba cổng tra cứu nội bộ vẫn hoạt động khi CallerDirectory không dùng được ngoài HTTP. */
    @Test
    void internalLookupsNeverReadHttpCaller() {
        when(callers.current()).thenThrow(new IllegalStateException("No HTTP request."));
        when(read.findByCode(BRANCH.code())).thenReturn(Optional.of(BRANCH));
        when(read.findById(BRANCH.id())).thenReturn(Optional.of(BRANCH));
        var nearbyQuery = ListNearbyBranchesQuery.from(10.76, 106.66, 1000.0);
        var nearby = new BranchDistanceSummary(BRANCH.id(), BRANCH.code(), BRANCH.name(), BRANCH.address(), 0.0);
        when(read.findWithinRadius(nearbyQuery)).thenReturn(List.of(nearby));
        assertSame(BRANCH, service.find(QUERY).orElseThrow());
        assertSame(BRANCH, service.findById(new FindBranchByIdQuery(BRANCH.id())).orElseThrow());
        assertEquals(List.of(nearby), service.list(nearbyQuery));
        verifyNoInteractions(callers);
        verify(read).findByCode(BRANCH.code());
        verify(read).findById(BRANCH.id());
        verify(read).findWithinRadius(nearbyQuery);
        verifyNoMoreInteractions(read);
    }

    /** Kiểm cả mã và loại lỗi để không nhầm một exception khác với việc chặn quyền. */
    private void assertForbidden(ErrorCode expected) {
        var failure = assertThrowsExactly(DomainException.class, () -> service.get(QUERY));
        assertEquals(expected, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }
}
