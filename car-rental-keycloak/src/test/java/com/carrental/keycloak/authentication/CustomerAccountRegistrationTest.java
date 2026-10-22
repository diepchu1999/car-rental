package com.carrental.keycloak.authentication;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.KeycloakContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm thao tác UserProvider dưới transaction: chỉ CUSTOMER, không ghi lại bằng chứng cũ. */
class CustomerAccountRegistrationTest {
    private EmployeeFlowFixture fixture;
    private RoleModel customerRole;
    private final Map<String, String> attributes = new HashMap<>();
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    /** UserModel ghi thuộc tính vào map để đọc lại đúng giá trị, không stub một kết quả cố định. */
    @BeforeEach void setUp() {
        fixture = new EmployeeFlowFixture();
        when(fixture.session.getContext()).thenReturn(mock(KeycloakContext.class));
        var realms = mock(RealmProvider.class);
        when(fixture.session.realms()).thenReturn(realms);
        when(realms.getRealm("car-rental-realm")).thenReturn(fixture.realm);
        customerRole = mock(RoleModel.class);
        when(fixture.realm.getRole("CUSTOMER")).thenReturn(customerRole);
        when(fixture.user.getFirstAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(fixture.user).setSingleAttribute(anyString(), anyString());
    }

    /** Khách mới chỉ có CUSTOMER, không nhận vai hay required action mặc định ngoài luồng đã chốt. */
    @Test void createsEnabledCustomerWithExplicitConsentOnly() {
        when(fixture.users.getUserByUsername(fixture.realm, "+84912345678")).thenReturn(null);
        when(fixture.users.addUser(eq(fixture.realm), anyString(), eq("+84912345678"), eq(false), eq(false)))
                .thenReturn(fixture.user);
        assertEquals("employee-id", accept("local-test-v1", NOW));
        verify(fixture.session.getContext()).setRealm(fixture.realm);
        verify(fixture.user).setEnabled(true);
        verify(fixture.user).grantRole(customerRole);
        assertEquals("local-test-v1", attributes.get(KeycloakCustomerAccounts.TERMS_VERSION));
        assertEquals(NOW.toString(), attributes.get(KeycloakCustomerAccounts.CONSENTED_AT));
        verify(fixture.user, never()).grantRole(fixture.employeeRole);
    }

    /** Nhân viên dùng lại UserModel hiện có, chỉ bổ sung CUSTOMER; không xoá vai hay mật khẩu tạm. */
    @Test void reusesEmployeeAccountWithoutChangingEmployeeRoleOrRequiredActions() {
        assertEquals("employee-id", accept("local-test-v1", NOW));
        verify(fixture.users, never()).addUser(any(), anyString(), anyString(), anyBoolean(), anyBoolean());
        verify(fixture.user).grantRole(customerRole);
        verify(fixture.user, never()).deleteRoleMapping(any());
        verify(fixture.user, never()).removeRequiredAction(anyString());
        verifyNoInteractions(fixture.credentials);
    }

    /** Gọi lại hoặc đổi phiên bản cấu hình không ghi đè bản và thời điểm đồng ý đầu tiên. */
    @Test void preservesOriginalConsentOnRepeatedAcceptance() {
        accept("local-test-v1", NOW);
        accept("local-test-v2", NOW.plusSeconds(3600));
        assertEquals("local-test-v1", attributes.get(KeycloakCustomerAccounts.TERMS_VERSION));
        assertEquals(NOW.toString(), attributes.get(KeycloakCustomerAccounts.CONSENTED_AT));
        verify(fixture.user, times(2)).setSingleAttribute(anyString(), anyString());
    }

    /** Tra khách đã đồng ý là thao tác chỉ đọc; không dùng phiên bản đang cấu hình để so lại. */
    @Test void findsConsentedCustomerWithoutWritingAttributes() {
        attributes.put(KeycloakCustomerAccounts.TERMS_VERSION, "older-version");
        attributes.put(KeycloakCustomerAccounts.CONSENTED_AT, NOW.minusSeconds(3600).toString());
        when(fixture.user.hasRole(customerRole)).thenReturn(true);
        assertEquals("employee-id", KeycloakCustomerAccounts.findConsentedInSession(
                fixture.session, "car-rental-realm", "+84912345678").orElseThrow());
        verify(fixture.user, never()).setSingleAttribute(anyString(), anyString());
    }

    /** Không có bằng chứng đồng ý thì không coi tài khoản là khách đã đăng ký, dù có sẵn vai. */
    @Test void missingConsentRequiresTermsScreen() {
        when(fixture.user.hasRole(customerRole)).thenReturn(true);
        assertTrue(KeycloakCustomerAccounts.findConsentedInSession(
                fixture.session, "car-rental-realm", "+84912345678").isEmpty());
        verify(fixture.user, never()).setSingleAttribute(anyString(), anyString());
    }

    /** Bằng chứng thiếu một nửa không được tự chữa bằng thời điểm hiện tại. */
    @Test void rejectsIncompleteConsentInsteadOfOverwritingIt() {
        attributes.put(KeycloakCustomerAccounts.TERMS_VERSION, "original-version");
        assertThrows(IllegalStateException.class, () -> accept("local-test-v2", NOW));
        verify(fixture.user, never()).setSingleAttribute(anyString(), anyString());
    }

    /** Không tự bật lại tài khoản đã bị quản trị khoá khi nhận sự đồng ý từ trình duyệt. */
    @Test void rejectsDisabledExistingAccountWithoutMutatingIt() {
        when(fixture.user.isEnabled()).thenReturn(false);
        assertThrows(CustomerAccountUnavailableException.class, () -> accept("local-test-v1", NOW));
        verify(fixture.user, never()).setEnabled(anyBoolean());
        verify(fixture.user, never()).setSingleAttribute(anyString(), anyString());
        verify(fixture.user, never()).grantRole(any());
    }

    /** Dùng đúng hàm được adapter gọi sau khi lấy khoá transaction; kiểm đồng thời thuộc integration. */
    private String accept(String version, Instant at) {
        return KeycloakCustomerAccounts.acceptInSession(fixture.session, "car-rental-realm", "+84912345678", version, at);
    }
}
