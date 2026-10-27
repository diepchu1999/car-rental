package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpResult;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.CredentialInput;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-130/809: vai xác minh dùng đúng hai yếu tố của luồng nhân viên, không được đi đường tắt. */
class DocumentVerifierLoginTest {
    /** Mật khẩu đúng chỉ mở bước SMS; phải có OTP được kho xác nhận mới hoàn tất luồng. */
    @Test void verifierNeedsPasswordThenSms() {
        var fixture = verifier();
        fixture.input.putSingle("username", "+84912345678");
        fixture.input.putSingle("password", "Local-Only-Test-Password");
        when(fixture.credentials.isValid(any(CredentialInput[].class))).thenReturn(true);
        new EmployeePasswordAuthenticator().action(fixture.context);
        verify(fixture.context).success("password");
        assertNotNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        assertNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));

        clearInvocations(fixture.context);
        fixture.notes.put(EmployeeLoginState.CHALLENGE_ID, "verifier-test-challenge");
        fixture.input.clear(); fixture.input.putSingle("action", "verify"); fixture.input.putSingle("otp", "123456");
        when(fixture.otp.verify(any(), anyString(), anyString(), anyString())).thenReturn(OtpResult.VERIFIED);
        new EmployeeSmsAuthenticator().action(fixture.context);
        verify(fixture.context).success();
        assertNotNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
    }

    /** Biết OTP hoặc có context user từ SSO không thay thế dấu mật khẩu của đúng phiên. */
    @Test void verifierCannotSkipPassword() {
        var fixture = verifier(); fixture.identifyEmployee();
        fixture.notes.put(EmployeeLoginState.CHALLENGE_ID, "verifier-test-challenge");
        fixture.input.putSingle("action", "verify"); fixture.input.putSingle("otp", "123456");
        new EmployeeSmsAuthenticator().action(fixture.context);
        fixture.assertNoSuccess(); verifyNoInteractions(fixture.otp);
    }

    /** Vai bị thu hồi sau bước mật khẩu làm SMS dừng, không dùng quyền đã hết hiệu lực. */
    @Test void revokedVerifierCannotFinishSms() {
        var fixture = verifier(); fixture.verificationForm();
        when(fixture.user.hasRole(fixture.employeeRole)).thenReturn(false);
        new EmployeeSmsAuthenticator().action(fixture.context);
        fixture.assertNoSuccess(); verifyNoInteractions(fixture.otp);
    }

    /** Không cho fixture STAFF mặc định che lỗi bỏ sót verifier trong danh sách nhân viên. */
    private static EmployeeFlowFixture verifier() {
        var fixture = new EmployeeFlowFixture();
        when(fixture.realm.getRole("BRANCH_STAFF")).thenReturn(null);
        when(fixture.realm.getRole("DOCUMENT_VERIFIER")).thenReturn(fixture.employeeRole);
        return fixture;
    }
}
