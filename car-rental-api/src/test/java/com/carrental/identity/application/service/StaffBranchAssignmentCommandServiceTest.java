package com.carrental.identity.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.AssignStaffBranchCommand;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadAccountRolesPort;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.application.view.StaffBranchAssignmentDetail;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm điều phối và quyền ở use case bằng cổng giả; không tuyên bố kiểm Keycloak, transaction hay đồng thời.
 * Ràng buộc và cuộc đua ở persistence được kiểm riêng bằng PostgreSQL thật.
 */
class StaffBranchAssignmentCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00.123456789Z");
    private static final Instant STORED_AT = Instant.parse("2026-10-15T03:00:00.123456Z");
    private static final AssignStaffBranchCommand COMMAND = AssignStaffBranchCommand.from("TK-EMP001", "CN-ABC123");
    private final CallerDirectory callers = mock(CallerDirectory.class);
    private final ReadAccountPort accounts = mock(ReadAccountPort.class);
    private final ReadAccountRolesPort roles = mock(ReadAccountRolesPort.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final WriteStaffBranchAssignmentPort write = mock(WriteStaffBranchAssignmentPort.class);
    private final ReadStaffBranchAssignmentPort read = mock(ReadStaffBranchAssignmentPort.class);
    private final Clock clock = mock(Clock.class);
    private final StaffBranchAssignmentCommandService service = new StaffBranchAssignmentCommandService(
            callers, accounts, roles, branches, write, read, clock);

    /** Dữ liệu mặc định hợp lệ, mọi nguồn danh tính và đồng hồ đều được kiểm soát trong test. */
    @BeforeEach
    void setUp() {
        when(callers.current()).thenReturn(new CallerRef("TK-ADM001", Set.of("ADMIN"), null));
        when(accounts.findByCode("TK-EMP001")).thenReturn(Optional.of(Account.restore("TK-EMP001", "employee-subject",
                new VietnamMobileNumber("0912345678"), null)));
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.of(new AccountRoles(Set.of(AccountRole.BRANCH_STAFF))));
        when(branches.findByCode("CN-ABC123")).thenReturn(Optional.of(new BranchRef(42L, "CN-ABC123")));
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-ABC123")));
        when(clock.instant()).thenReturn(NOW);
        when(write.save(any())).thenReturn(true);
        when(read.findByAccountCode("TK-EMP001")).thenReturn(Optional.of(stored(AccountRole.BRANCH_STAFF)));
    }

    /** ADMIN gán đủ bốn vai, vai lấy từ nguồn danh tính đích; trả thời điểm đọc lại, không trả candidate. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void adminCanAssignEveryBranchScopedRole(AccountRole role) {
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.of(new AccountRoles(Set.of(role))));
        when(read.findByAccountCode("TK-EMP001")).thenReturn(Optional.of(stored(role)));
        assertEquals(new StaffBranchAssignmentDetail("TK-EMP001", "CN-ABC123", role.name(), STORED_AT), service.assign(COMMAND));
        var assignment = ArgumentCaptor.forClass(StaffBranchAssignment.class);
        var order = inOrder(callers, accounts, roles, branches, write, read);
        order.verify(callers).current();
        order.verify(accounts).findByCode("TK-EMP001");
        order.verify(roles).findBySubject("employee-subject");
        order.verify(branches).findByCode("CN-ABC123");
        order.verify(write).save(assignment.capture());
        order.verify(read).findByAccountCode("TK-EMP001");
        order.verify(branches).findById(42L);
        assertEquals(new StaffBranchAssignment("TK-EMP001", 42L, role, NOW), assignment.getValue());
        verify(clock).instant();
    }

    /** Mọi vai ngoài ADMIN bị từ chối trước khi dò tài khoản, gọi Keycloak hoặc đọc chi nhánh đích. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, mode = EnumSource.Mode.EXCLUDE, names = "ADMIN")
    void rejectsEveryNonAdminCallerBeforeTargetLookups(AccountRole role) {
        when(callers.current()).thenReturn(new CallerRef("TK-ADM001", Set.of(role.name()), null));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN, () -> service.assign(COMMAND));
        verifyNoInteractions(accounts, roles, branches, write, read, clock);
    }

    /** Người gọi không có vai cũng không được quyền gán chi nhánh. */
    @Test
    void rejectsCallerWithoutRoles() {
        when(callers.current()).thenReturn(new CallerRef("TK-ADM001", Set.of(), null));
        assertFailure(ErrorCode.ACCESS_DENIED, DomainException.Category.FORBIDDEN, () -> service.assign(COMMAND));
        verifyNoInteractions(accounts, roles, branches, write, read, clock);
    }

    /** CUSTOMER đi kèm ADMIN hoặc vai đích hợp lệ không làm mất quyền hay sinh vai nhân viên thứ hai. */
    @Test
    void permitsCustomerAlongsideAdminAndTargetEmployee() {
        when(callers.current()).thenReturn(new CallerRef("TK-ADM001", Set.of("ADMIN", "CUSTOMER"), null));
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.of(
                new AccountRoles(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF))));
        assertEquals("BRANCH_STAFF", service.assign(COMMAND).staffRole());
    }

    /** Thiếu xác thực hoặc gọi ngoài HTTP không được biến thành quyền hệ thống. */
    @Test
    void propagatesMissingCallerContextBeforeAnyLookup() {
        for (RuntimeException failure : new RuntimeException[]{DomainException.unauthenticated(),
                new IllegalStateException("CallerDirectory requires an active HTTP request.")}) {
            doThrow(failure).when(callers).current();
            assertSame(failure, assertThrows(RuntimeException.class, () -> service.assign(COMMAND)));
        }
        verifyNoInteractions(accounts, roles, branches, write, read, clock);
    }

    /** Không tự tạo tài khoản backend khi ADMIN gửi một mã không tồn tại. */
    @Test
    void rejectsMissingBusinessAccount() {
        when(accounts.findByCode("TK-EMP001")).thenReturn(Optional.empty());
        assertFailure(ErrorCode.ACCOUNT_NOT_FOUND, DomainException.Category.NOT_FOUND, () -> service.assign(COMMAND));
        verifyNoInteractions(roles, branches, write, read, clock);
    }

    /** Backend có tài khoản nhưng Keycloak không có subject thì không dùng vai đã lưu để gán. */
    @Test
    void rejectsMissingIdentityProviderUser() {
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.empty());
        assertFailure(ErrorCode.ACCOUNT_NOT_FOUND, DomainException.Category.NOT_FOUND, () -> service.assign(COMMAND));
        verifyNoInteractions(branches, write, read, clock);
    }

    /** Vai cấp công ty, khách và đối tác không được gán như nhân viên thuộc chi nhánh. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void rejectsNonBranchScopedTargetRole(AccountRole role) {
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.of(new AccountRoles(Set.of(role))));
        assertFailure(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED, DomainException.Category.RULE_VIOLATION,
                () -> service.assign(COMMAND));
        verifyNoInteractions(branches, write, read, clock);
    }

    /** Người dùng tồn tại nhưng không có vai dự án không bị coi là nhân viên. */
    @Test
    void rejectsTargetWithoutProjectRoles() {
        when(roles.findBySubject("employee-subject")).thenReturn(Optional.of(new AccountRoles(Set.of())));
        assertFailure(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED, DomainException.Category.RULE_VIOLATION,
                () -> service.assign(COMMAND));
        verifyNoInteractions(branches, write, read, clock);
    }

    /** Lỗi nguồn vai đi nguyên ra ngoài; không giả thành tập vai rỗng hay lấy vai ADMIN của người thực hiện. */
    @Test
    void propagatesIdentityProviderFailureWithoutWriting() {
        var failure = new IllegalStateException("Identity provider unavailable.");
        when(roles.findBySubject("employee-subject")).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service.assign(COMMAND)));
        verifyNoInteractions(branches, write, read, clock);
    }

    /** Không ghi ID chi nhánh đoán mò nếu branch.api không tìm thấy mã. */
    @Test
    void rejectsMissingBranchBeforeWriting() {
        when(branches.findByCode("CN-ABC123")).thenReturn(Optional.empty());
        assertFailure(ErrorCode.BRANCH_NOT_FOUND, DomainException.Category.NOT_FOUND, () -> service.assign(COMMAND));
        verifyNoInteractions(write, read, clock);
    }

    /** Tài khoản mất trước lúc ghi trả đúng ACCOUNT_NOT_FOUND, không trả bản gán chưa lưu. */
    @Test
    void rejectsAccountMissingAtWriteTime() {
        when(write.save(any())).thenReturn(false);
        assertFailure(ErrorCode.ACCOUNT_NOT_FOUND, DomainException.Category.NOT_FOUND, () -> service.assign(COMMAND));
        verifyNoInteractions(read);
        verify(branches, never()).findById(anyLong());
    }

    /** Xung đột quản lý giữ nguyên lỗi 409 và không thử lại hay đọc kết quả giả sau lỗi. */
    @Test
    void propagatesManagerConflictWithoutRetryOrReload() {
        var failure = DomainException.conflict(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED);
        when(write.save(any())).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(DomainException.class, () -> service.assign(COMMAND)));
        verify(write).save(any());
        verifyNoInteractions(read);
        verify(branches, never()).findById(anyLong());
    }

    /** Thiếu bản gán sau ghi là lỗi nội bộ, không trả aggregate còn trong bộ nhớ. */
    @Test
    void missingReloadIsAnInternalFailure() {
        when(read.findByAccountCode("TK-EMP001")).thenReturn(Optional.empty());
        assertThrowsExactly(IllegalStateException.class, () -> service.assign(COMMAND));
        verify(branches, never()).findById(anyLong());
    }

    /** Dữ liệu đọc lại lệch tài khoản, chi nhánh hoặc vai không được trả như kết quả thành công. */
    @ParameterizedTest
    @ValueSource(strings = {"account", "branch", "role"})
    void mismatchedReloadIsAnInternalFailure(String field) {
        var wrong = new StaffBranchAssignment(field.equals("account") ? "TK-OTH001" : "TK-EMP001",
                field.equals("branch") ? 43L : 42L,
                field.equals("role") ? AccountRole.BRANCH_MANAGER : AccountRole.BRANCH_STAFF, STORED_AT);
        when(read.findByAccountCode("TK-EMP001")).thenReturn(Optional.of(wrong));
        assertThrowsExactly(IllegalStateException.class, () -> service.assign(COMMAND));
        verify(branches, never()).findById(anyLong());
    }

    /** Không tra được mã từ ID đã lưu là lỗi tham chiếu nội bộ, không lộ ID ra response thay thế. */
    @Test
    void missingBranchDuringReloadIsAnInternalFailure() {
        when(branches.findById(42L)).thenReturn(Optional.empty());
        assertThrowsExactly(IllegalStateException.class, () -> service.assign(COMMAND));
    }

    /** Command null của ADMIN bị từ chối trước tra cứu hoặc ghi dữ liệu. */
    @Test
    void rejectsMissingCommand() {
        assertFailure(ErrorCode.INVALID_REQUEST, DomainException.Category.INVALID_INPUT, () -> service.assign(null));
        verifyNoInteractions(accounts, roles, branches, write, read, clock);
    }

    /** Dữ liệu đọc lại có độ chính xác timestamp khác đồng hồ để bắt việc trả candidate thay reload. */
    private static StaffBranchAssignment stored(AccountRole role) {
        return new StaffBranchAssignment("TK-EMP001", 42L, role, STORED_AT);
    }

    /** Kiểm cả mã lẫn nhóm lỗi, không chỉ kiểm có exception. */
    private static void assertFailure(ErrorCode code, DomainException.Category category,
                                      org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
    }
}
