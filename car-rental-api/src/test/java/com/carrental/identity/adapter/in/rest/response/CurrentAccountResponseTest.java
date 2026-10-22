package com.carrental.identity.adapter.in.rest.response;

import com.carrental.identity.application.view.CurrentAccountDetail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng ánh xạ của /me, tính bất biến và tránh lộ dữ liệu khi response được in vào log. */
class CurrentAccountResponseTest {
    /** Ánh xạ giữ đúng consent gốc và mã chi nhánh, không tính lại hoặc lấy dữ liệu từ request. */
    @Test
    void mapsAllPublicFields() {
        var at = Instant.parse("2026-10-12T03:00:00Z");
        var view = new CurrentAccountDetail("TK-SELF01", "+84912345678", Set.of("BRANCH_STAFF"),
                "CN-LOCAL1", "local-test-v1", at);

        assertEquals(new CurrentAccountResponse("TK-SELF01", "+84912345678", Set.of("BRANCH_STAFF"),
                "CN-LOCAL1", "local-test-v1", at), CurrentAccountResponse.fromDomain(view));
    }

    /** View thiếu là lỗi lập trình, không tạo response 200 với dữ liệu tài khoản rỗng. */
    @Test
    void rejectsMissingView() {
        assertThrows(NullPointerException.class, () -> CurrentAccountResponse.fromDomain(null));
    }

    /** Thay collection đầu vào không đổi quyền hiển thị; toString chỉ in mã nghiệp vụ. */
    @Test
    void copiesRolesAndRedactsRepresentation() {
        var roles = new HashSet<>(Set.of("CUSTOMER"));
        var response = new CurrentAccountResponse("TK-SELF01", "+84912345678", roles, null, null, null);
        roles.add("ADMIN");

        assertEquals(Set.of("CUSTOMER"), response.roles());
        assertThrows(UnsupportedOperationException.class, () -> response.roles().add("ADMIN"));
        assertEquals("CurrentAccountResponse[code=TK-SELF01]", response.toString());
    }
}
