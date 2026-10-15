package com.carrental.identity.api;

import com.carrental.identity.domain.AccountRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng người gọi dùng chung không làm rò domain hoặc tự nâng quyền theo BR-801/809. */
class CallerRefTest {
    /** Mọi vai đã chốt biểu diễn được bằng tên chuẩn, không xuất kiểu enum domain qua chữ ký API. */
    @ParameterizedTest
    @EnumSource(AccountRole.class)
    void acceptsEveryDeclaredRole(AccountRole role) {
        var caller = new CallerRef("TK-ABC123", Set.of(role.name()), null);
        assertTrue(caller.hasRole(role.name()));
        assertEquals("TK-ABC123", caller.accountCode());
        assertNull(caller.branchId());
    }

    /** Tập vai được sao chép và bất biến; sửa tập đầu vào không nâng quyền của caller. */
    @Test
    void copiesRolesAndDoesNotImplyOtherPermissions() {
        var roles = new HashSet<>(Set.of("CUSTOMER", "BRANCH_MANAGER"));
        var caller = new CallerRef("TK-ABC123", roles, 42L);
        roles.add("ADMIN");
        assertFalse(caller.hasRole("ADMIN"));
        assertFalse(caller.hasRole("BRANCH_STAFF"));
        assertTrue(caller.hasRole("CUSTOMER"));
        assertEquals(42L, caller.branchId().longValue());
        assertThrows(UnsupportedOperationException.class, () -> caller.roles().add("ADMIN"));
    }

    /** Tên vai sai hoặc nhiều vai nhân viên không được hợp thức hoá ở hợp đồng cross-module. */
    @Test
    void rejectsInvalidRoleSets() {
        assertThrowsExactly(IllegalArgumentException.class, () -> new CallerRef("TK-ABC123", Set.of("admin"), null));
        assertThrowsExactly(IllegalArgumentException.class, () -> new CallerRef("TK-ABC123", Set.of("UNKNOWN"), null));
        assertThrowsExactly(IllegalArgumentException.class,
                () -> new CallerRef("TK-ABC123", Set.of("ADMIN", "BRANCH_STAFF"), null));
        assertThrowsExactly(NullPointerException.class, () -> new CallerRef("TK-ABC123", null, null));
        var withNull = new HashSet<String>();
        withNull.add(null);
        assertThrowsExactly(NullPointerException.class, () -> new CallerRef("TK-ABC123", withNull, null));
    }

    /** Tập vai rỗng không cấp quyền; chi nhánh thiếu không bị thay bằng giá trị giả. */
    @Test
    void missingRolesOrBranchNeverInventPermissions() {
        var caller = new CallerRef("TK-ABC123", Set.of(), null);
        assertFalse(caller.hasRole("ADMIN"));
        assertFalse(caller.hasRole("CUSTOMER"));
        assertNull(caller.branchId());
        assertNull(new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), null).branchId());
    }

    /** Mã tài khoản sai và ID chi nhánh không dương là lỗi cấu trúc dữ liệu nội bộ. */
    @Test
    void rejectsInvalidIdentityStructure() {
        for (String code : new String[]{"", " ", "TK-abc123", "TK-ABC12", "CN-ABC123", " TK-ABC123"}) {
            assertThrowsExactly(IllegalArgumentException.class, () -> new CallerRef(code, Set.of(), null));
        }
        assertThrowsExactly(NullPointerException.class, () -> new CallerRef(null, Set.of(), null));
        assertThrowsExactly(IllegalArgumentException.class, () -> new CallerRef("TK-ABC123", Set.of(), 0L));
        assertThrowsExactly(IllegalArgumentException.class, () -> new CallerRef("TK-ABC123", Set.of(), -1L));
    }

    /** Dò vai giữ nguyên chữ hoa/thường, không chấp nhận prefix hay khoảng trắng như vai thật. */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "ROLE_ADMIN", " ADMIN", "ADMIN ", ""})
    void requiresAnExactRoleName(String role) {
        assertFalse(new CallerRef("TK-ABC123", Set.of("ADMIN"), null).hasRole(role));
    }

    /** Biểu diễn log chỉ có mã; tên vai null là lỗi lập trình chứ không được mặc định thành quyền. */
    @Test
    void limitsLogRepresentationAndRejectsNullRoleLookup() {
        var caller = new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), 42L);
        assertEquals("CallerRef[accountCode=TK-ABC123]", caller.toString());
        assertThrowsExactly(NullPointerException.class, () -> caller.hasRole(null));
    }
}
