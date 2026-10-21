package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.query.GetCallerQuery;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm ghép danh tính và phạm vi theo BR-803/809, bằng cổng đọc giả và vai đầu vào đã xác thực.
 * Không thay thế test JWT, PostgreSQL, transaction hoặc cuộc đua gán quản lý đã có.
 */
class CallerQueryServiceTest {
    private static final String ACCOUNT_CODE = "TK-EMP001";
    private static final Instant ASSIGNED_AT = Instant.parse("2026-10-15T03:00:00Z");
    private final ReadStaffBranchAssignmentPort assignments = mock(ReadStaffBranchAssignmentPort.class);
    private final CallerQueryService service = new CallerQueryService(assignments);

    /** Cả bốn vai chi nhánh dùng đúng bản gán của tài khoản đã đồng bộ, không dựa vào thứ tự vai. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void resolvesEveryBranchScopedRole(AccountRole role) {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(Optional.of(assignment(ACCOUNT_CODE, 42L, role)));
        assertEquals(new CallerRef(ACCOUNT_CODE, Set.of(role.name()), 42L), service.get(query(role)));
        verify(assignments).findByAccountCode(ACCOUNT_CODE);
        verifyNoMoreInteractions(assignments);
    }

    /** CUSTOMER đi kèm nhân viên không làm mất vai thuê xe hoặc phạm vi chi nhánh của nhân viên. */
    @Test
    void preservesCustomerAlongsideEmployee() {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(Optional.of(
                assignment(ACCOUNT_CODE, 42L, AccountRole.BRANCH_MANAGER)));
        var query = GetCallerQuery.from(ACCOUNT_CODE,
                new AccountRoles(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_MANAGER)));
        assertEquals(new CallerRef(ACCOUNT_CODE, Set.of("CUSTOMER", "BRANCH_MANAGER"), 42L), service.get(query));
    }

    /** Chưa gán được biểu diễn bằng null để đọc /me, tuyệt đối không đoán một chi nhánh mặc định. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void leavesUnassignedEmployeeWithoutBranch(AccountRole role) {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(Optional.empty());
        assertEquals(new CallerRef(ACCOUNT_CODE, Set.of(role.name()), null), service.get(query(role)));
        verify(assignments).findByAccountCode(ACCOUNT_CODE);
    }

    /** Vai cấp công ty/khách/đối tác không đọc bản gán cũ và không tự nâng thành ADMIN. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void ignoresBranchAssignmentsForNonBranchRoles(AccountRole role) {
        assertEquals(new CallerRef(ACCOUNT_CODE, Set.of(role.name()), null), service.get(query(role)));
        verifyNoInteractions(assignments);
    }

    /** Tập vai rỗng vẫn rỗng; không suy nhân viên hay khách từ việc đã có tài khoản. */
    @Test
    void doesNotInventRolesForAccountWithoutRoles() {
        assertEquals(new CallerRef(ACCOUNT_CODE, Set.of(), null),
                service.get(GetCallerQuery.from(ACCOUNT_CODE, new AccountRoles(Set.of()))));
        verifyNoInteractions(assignments);
    }

    /** Mọi cặp vai chi nhánh lệch nhau đều trả đúng STAFF_ROLE_MISMATCH, không dùng quyền của bản gán. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void rejectsAssignmentWithDifferentCurrentRole(AccountRole currentRole) {
        for (AccountRole storedRole : AccountRole.values()) {
            if (!storedRole.isBranchScoped() || storedRole == currentRole) continue;
            when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(Optional.of(
                    assignment(ACCOUNT_CODE, 42L, storedRole)));
            var failure = assertThrowsExactly(DomainException.class, () -> service.get(query(currentRole)));
            assertEquals(ErrorCode.STAFF_ROLE_MISMATCH, failure.errorCode());
            assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        }
    }

    /** Thay bản gán giữa hai lượt gọi phải được thấy ngay, không cache nhánh trên bean hoặc token. */
    @Test
    void readsAssignmentAgainOnEveryCall() {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(
                Optional.of(assignment(ACCOUNT_CODE, 42L, AccountRole.BRANCH_STAFF)),
                Optional.of(assignment(ACCOUNT_CODE, 73L, AccountRole.BRANCH_STAFF)), Optional.empty());
        var first = service.get(query(AccountRole.BRANCH_STAFF));
        var second = service.get(query(AccountRole.BRANCH_STAFF));
        var third = service.get(query(AccountRole.BRANCH_STAFF));
        assertEquals(Long.valueOf(42L), first.branchId());
        assertEquals(Long.valueOf(73L), second.branchId());
        assertNull(third.branchId());
        verify(assignments, times(3)).findByAccountCode(ACCOUNT_CODE);
    }

    /** Đọc hỏng không được coi là chưa gán để tiếp tục bằng danh tính thiếu phạm vi. */
    @Test
    void propagatesStorageFailure() {
        var failure = new IllegalStateException("Storage unavailable.");
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class,
                () -> service.get(query(AccountRole.BRANCH_STAFF))));
    }

    /** Adapter trả nhầm tài khoản không được làm lộ hoặc dùng chi nhánh của người khác. */
    @Test
    void rejectsAssignmentForDifferentAccount() {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(Optional.of(
                assignment("TK-OTHER1", 42L, AccountRole.BRANCH_STAFF)));
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> service.get(query(AccountRole.BRANCH_STAFF)));
        assertEquals("Staff branch lookup returned a different account.", failure.getMessage());
    }

    /** Port phải trả Optional, null là lỗi nội bộ chứ không đồng nghĩa chưa gán. */
    @Test
    void rejectsNullLookupResult() {
        when(assignments.findByAccountCode(ACCOUNT_CODE)).thenReturn(null);
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> service.get(query(AccountRole.BRANCH_STAFF)));
        assertEquals("Staff branch lookup returned no result container.", failure.getMessage());
    }

    /** Query thiếu phải bị chặn trước khi đọc CSDL, không vô tình dùng trạng thái lần gọi trước. */
    @Test
    void rejectsMissingQueryBeforeLookup() {
        assertInvalid(assertThrowsExactly(DomainException.class, () -> service.get(null)));
        verifyNoInteractions(assignments);
    }

    /** Cả factory và constructor không cho bỏ mã tài khoản, không tự tìm tài khoản dựa vào vai. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsMissingAccountCode(String code) {
        var roles = new AccountRoles(Set.of(AccountRole.ADMIN));
        assertInvalid(assertThrowsExactly(DomainException.class, () -> GetCallerQuery.from(code, roles)));
        assertInvalid(assertThrowsExactly(DomainException.class, () -> new GetCallerQuery(code, roles)));
        verifyNoInteractions(assignments);
    }

    /** Phân biệt tập vai rỗng hợp lệ với việc thiếu nguồn vai, không bỏ qua lỗi lập trình. */
    @Test
    void rejectsMissingRoles() {
        assertInvalid(assertThrowsExactly(DomainException.class, () -> GetCallerQuery.from(ACCOUNT_CODE, null)));
        verifyNoInteractions(assignments);
    }

    /** Query không tự in tập vai khi vô tình được đưa vào log. */
    @Test
    void redactsQueryRepresentation() {
        assertEquals("GetCallerQuery[redacted]", query(AccountRole.ADMIN).toString());
    }

    /** Vai được cấp tường minh trong fixture, không gọi Keycloak hoặc suy từ bản gán. */
    private GetCallerQuery query(AccountRole role) {
        return GetCallerQuery.from(ACCOUNT_CODE, new AccountRoles(Set.of(role)));
    }

    /** Bản gán hợp lệ dùng để tập trung vào kiểm phạm vi; constraint đã có test PostgreSQL riêng. */
    private StaffBranchAssignment assignment(String accountCode, long branchId, AccountRole role) {
        return new StaffBranchAssignment(accountCode, branchId, role, ASSIGNED_AT);
    }

    /** Đầu vào thiếu phải đúng lỗi validation, không xanh nhờ NullPointerException. */
    private void assertInvalid(DomainException failure) {
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
