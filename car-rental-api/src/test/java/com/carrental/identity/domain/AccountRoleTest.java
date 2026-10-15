package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Kiểm danh sách vai và phạm vi BR-801/803 độc lập với Spring Security. */
class AccountRoleTest {

    /** Tên của cả mười hai vai phải khớp hợp đồng, không thiếu hoặc thêm vai tự suy diễn. */
    @Test
    void containsExactlyTheTwelveBusinessRoles() {
        assertEquals(Set.of("CUSTOMER", "BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
                        "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN", "HOST",
                        "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"),
                Arrays.stream(AccountRole.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    /** Chỉ bảy vai công ty là nhân viên; tên chứa STAFF hoặc DRIVER không đủ để cấp quyền. */
    @Test
    void identifiesExactlyTheCompanyEmployeeRoles() {
        Set<AccountRole> employees = Set.of(AccountRole.BRANCH_STAFF, AccountRole.BRANCH_MANAGER,
                AccountRole.DELIVERY_STAFF, AccountRole.DRIVER, AccountRole.ACCOUNTANT,
                AccountRole.OPERATIONS_MANAGER, AccountRole.ADMIN);
        for (AccountRole role : AccountRole.values()) {
            assertEquals(employees.contains(role), role.isEmployee(), role.name());
        }
    }

    /** BR-803: ADMIN và hai vai cấp công ty không bị yêu cầu gán chi nhánh. */
    @Test
    void identifiesExactlyTheFourBranchScopedRoles() {
        Set<AccountRole> branchRoles = Set.of(AccountRole.BRANCH_STAFF, AccountRole.BRANCH_MANAGER,
                AccountRole.DELIVERY_STAFF, AccountRole.DRIVER);
        for (AccountRole role : AccountRole.values()) {
            assertEquals(branchRoles.contains(role), role.isBranchScoped(), role.name());
        }
    }
}
