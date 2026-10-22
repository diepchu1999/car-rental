package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm bản gán và phát hiện lệch vai theo BR-803/809; không thay thế test UNIQUE thật của BR-807. */
class StaffBranchAssignmentTest {
    private static final Instant AT = Instant.parse("2026-10-13T03:00:00Z");

    /** Cả bốn vai thuộc chi nhánh giữ chính xác dữ liệu, không suy vai mạnh hơn. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void acceptsEveryBranchScopedRole(AccountRole role) {
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, role, AT);
        assertEquals("TK-ABC123", assignment.accountCode());
        assertEquals(42L, assignment.branchId());
        assertEquals(role, assignment.staffRole());
        assertEquals(AT, assignment.assignedAt());
        assertDoesNotThrow(() -> assignment.requireCurrentRole(new AccountRoles(Set.of(role))));
    }

    /** Các vai cấp công ty, khách và đối tác không được ghi thành vai của bản gán chi nhánh. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void rejectsRolesOutsideBranchScope(AccountRole role) {
        var failure = assertThrowsExactly(DomainException.class,
                () -> new StaffBranchAssignment("TK-ABC123", 42L, role, AT));
        assertEquals(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** CUSTOMER đi kèm không làm thay đổi hoặc vô hiệu vai nhân viên đã khớp. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void permitsCustomerAlongsideTheMatchingEmployeeRole(AccountRole role) {
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, role, AT);
        assertDoesNotThrow(() -> assignment.requireCurrentRole(new AccountRoles(Set.of(AccountRole.CUSTOMER, role))));
        assertEquals(role, assignment.staffRole());
    }

    /** Vai hiện tại khác vai quản lý đã lưu phải bị chặn, không nâng quyền từ bản gán. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, mode = EnumSource.Mode.EXCLUDE, names = "BRANCH_MANAGER")
    void rejectsEveryDifferentCurrentRole(AccountRole currentRole) {
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, AccountRole.BRANCH_MANAGER, AT);
        assertMismatch(() -> assignment.requireCurrentRole(new AccountRoles(Set.of(currentRole))));
    }

    /** Hạ vai quản lý xuống nhân viên hoặc ngược lại đều làm bản gán cũ không còn khớp. */
    @Test
    void doesNotTreatManagerAsAnImplicitStaffRole() {
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, AccountRole.BRANCH_STAFF, AT);
        assertMismatch(() -> assignment.requireCurrentRole(new AccountRoles(Set.of(AccountRole.BRANCH_MANAGER))));
    }

    /** Mất hết vai không được tiếp tục dùng bản gán như nguồn cấp quyền. */
    @Test
    void rejectsEmptyCurrentRoles() {
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, AccountRole.BRANCH_STAFF, AT);
        assertMismatch(() -> assignment.requireCurrentRole(new AccountRoles(Set.of())));
    }

    /** Mã sai không được tự cắt khoảng trắng hoặc đổi chữ hoa. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "TK-abc123", "TK-ABC12", "TK-ABC1234", "CN-ABC123", " TK-ABC123"})
    void rejectsInvalidAccountCode(String code) {
        assertInvalid(() -> new StaffBranchAssignment(code, 42L, AccountRole.BRANCH_STAFF, AT));
    }

    /** ID nội bộ phải dương; số 0 hoặc âm không được dùng làm giá trị chi nhánh giả. */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveBranchId(long branchId) {
        assertInvalid(() -> new StaffBranchAssignment("TK-ABC123", branchId, AccountRole.BRANCH_STAFF, AT));
    }

    /** Không tự điền vai, đồng hồ hệ thống hoặc tập vai rỗng khi đối số bị thiếu. */
    @Test
    void rejectsMissingRoleTimeAndCurrentRoles() {
        assertInvalid(() -> new StaffBranchAssignment("TK-ABC123", 42L, null, AT));
        assertInvalid(() -> new StaffBranchAssignment("TK-ABC123", 42L, AccountRole.BRANCH_STAFF, null));
        var assignment = new StaffBranchAssignment("TK-ABC123", 42L, AccountRole.BRANCH_STAFF, AT);
        assertInvalid(() -> assignment.requireCurrentRole(null));
    }

    /** Phải đúng lỗi 403 dự kiến, không xanh nhờ lỗi lập trình hoặc trả 422 nhầm. */
    private static void assertMismatch(Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.STAFF_ROLE_MISMATCH, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
    }

    /** Đầu vào cấu trúc sai phải có mã chung an toàn, không lộ dữ liệu truyền vào. */
    private static void assertInvalid(Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
