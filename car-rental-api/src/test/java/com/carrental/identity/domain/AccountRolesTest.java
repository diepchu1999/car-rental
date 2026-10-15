package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm bất biến một vai nhân viên và việc không vô tình cấp thêm quyền, BR-809. */
class AccountRolesTest {

    /** Khách không cần vai nhân viên để có tập vai hợp lệ. */
    @Test
    void acceptsCustomerWithoutEmployeeRole() {
        AccountRoles roles = new AccountRoles(Set.of(AccountRole.CUSTOMER));
        assertTrue(roles.has(AccountRole.CUSTOMER));
        assertTrue(roles.employeeRole().isEmpty());
    }

    /** Mỗi vai nhân viên được đứng riêng hoặc đồng thời là khách, không bắt nhân viên đăng ký thuê. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN"})
    void acceptsEmployeeAloneOrAlongsideCustomer(AccountRole employee) {
        AccountRoles staffOnly = new AccountRoles(Set.of(employee));
        assertEquals(employee, staffOnly.employeeRole().orElseThrow());
        assertFalse(staffOnly.has(AccountRole.CUSTOMER));
        AccountRoles customerAndStaff = new AccountRoles(Set.of(AccountRole.CUSTOMER, employee));
        assertEquals(employee, customerAndStaff.employeeRole().orElseThrow());
        assertTrue(customerAndStaff.has(AccountRole.CUSTOMER));
    }

    /** Thử mọi cặp nhân viên, kể cả có ADMIN: phải từ chối thay vì ưu tiên vai mạnh hơn. */
    @Test
    void rejectsEveryPairOfEmployeeRoles() {
        AccountRole[] employees = {AccountRole.BRANCH_STAFF, AccountRole.BRANCH_MANAGER,
                AccountRole.DELIVERY_STAFF, AccountRole.DRIVER, AccountRole.ACCOUNTANT,
                AccountRole.OPERATIONS_MANAGER, AccountRole.ADMIN};
        for (int first = 0; first < employees.length; first++) {
            for (int second = first + 1; second < employees.length; second++) {
                Set<AccountRole> pair = Set.of(employees[first], employees[second]);
                assertThrowsExactly(IllegalArgumentException.class, () -> new AccountRoles(pair));
                Set<AccountRole> withCustomer = EnumSet.copyOf(pair);
                withCustomer.add(AccountRole.CUSTOMER);
                assertThrowsExactly(IllegalArgumentException.class, () -> new AccountRoles(withCustomer));
            }
        }
    }

    /** Không suy quyền ADMIN từ vai cấp công ty, cũng không tự thêm CUSTOMER. */
    @Test
    void doesNotInferAdditionalPermissions() {
        for (AccountRole role : AccountRole.values()) {
            AccountRoles roles = new AccountRoles(Set.of(role));
            for (AccountRole requested : AccountRole.values()) {
                assertEquals(role == requested, roles.has(requested));
            }
        }
    }

    /** Không có vai thì không có quyền, không tự chọn CUSTOMER hoặc ADMIN làm mặc định. */
    @Test
    void emptyRolesGrantNothing() {
        AccountRoles roles = new AccountRoles(Set.of());
        assertTrue(roles.employeeRole().isEmpty());
        for (AccountRole role : AccountRole.values()) {
            assertFalse(roles.has(role));
        }
    }

    /** Vai đối tác hoặc tài xế tự do không bị nhận nhầm thành nhân viên công ty. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class,
            names = {"HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void futureRolesDoNotGrantEmployeeIdentity(AccountRole role) {
        assertTrue(new AccountRoles(Set.of(role)).employeeRole().isEmpty());
    }

    /** Sửa tập nguồn hoặc tập trả về không được đổi quyền sau khi dựng đối tượng. */
    @Test
    void roleSetIsDefensivelyCopiedAndImmutable() {
        Set<AccountRole> source = EnumSet.of(AccountRole.CUSTOMER);
        AccountRoles roles = new AccountRoles(source);
        source.add(AccountRole.ADMIN);
        assertEquals(Set.of(AccountRole.CUSTOMER), roles.values());
        assertThrowsExactly(UnsupportedOperationException.class,
                () -> roles.values().add(AccountRole.ADMIN));
    }

    /** Null là lỗi hợp đồng nội bộ, không được hiểu thành người gọi ẩn danh hoặc không có vai. */
    @Test
    void rejectsNullInputs() {
        assertThrowsExactly(NullPointerException.class, () -> new AccountRoles(null));
        Set<AccountRole> withNull = new HashSet<>();
        withNull.add(null);
        assertThrowsExactly(NullPointerException.class, () -> new AccountRoles(withNull));
        AccountRoles roles = new AccountRoles(Set.of());
        assertThrowsExactly(NullPointerException.class, () -> roles.has(null));
    }
}
