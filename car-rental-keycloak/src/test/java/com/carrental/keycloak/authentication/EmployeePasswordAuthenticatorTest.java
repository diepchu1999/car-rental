package com.carrental.keycloak.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.credential.CredentialInput;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm lớp nối mật khẩu thật của Keycloak: không tự so mật khẩu và không gửi SMS khi chưa hợp lệ. */
class EmployeePasswordAuthenticatorTest {
    /** Hai cách nhập cùng về E.164; gọi đúng credential manager trước khi đánh dấu mật khẩu. */
    @ParameterizedTest @ValueSource(strings = {"0912345678", "+84912345678"})
    void normalizesPhoneAndDelegatesPasswordValidation(String phone) {
        var fixture = new EmployeeFlowFixture();
        fixture.input.putSingle("username", phone);
        fixture.input.putSingle("password", "FakePassword-Only-For-Test");
        when(fixture.credentials.isValid(any(CredentialInput[].class))).thenReturn(true);

        new EmployeePasswordAuthenticator().action(fixture.context);

        verify(fixture.users).getUserByUsername(fixture.realm, "+84912345678");
        // Captor khai báo rõ kiểu mảng để Mockito không áp matcher lên từng phần tử varargs.
        var credentialCaptor = ArgumentCaptor.forClass(CredentialInput[].class);
        verify(fixture.credentials).isValid(credentialCaptor.capture());
        CredentialInput[] values = credentialCaptor.getValue();
        assertEquals(1, values.length);
        assertEquals("password", values[0].getType());
        assertEquals("FakePassword-Only-For-Test", values[0].getChallengeResponse());
        assertEquals(EmployeeLoginState.binding(fixture.context), fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        verify(fixture.context).success("password");
        assertNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
        verifyNoInteractions(fixture.otp);
    }

    /** Thiếu hoặc sai mật khẩu đều không tạo dấu; dấu MFA cũ cũng không được giữ lại. */
    @ParameterizedTest @ValueSource(strings = {"", "wrong-password"})
    void rejectsMissingOrWrongPasswordAndClearsPreviousProof(String password) {
        var fixture = new EmployeeFlowFixture();
        fixture.passwordVerified();
        fixture.notes.put(EmployeeLoginState.CHALLENGE_ID, "old-challenge");
        fixture.notes.put(EmployeeLoginState.MFA_VERIFIED, "old-proof");
        fixture.input.putSingle("username", "0912345678");
        fixture.input.putSingle("password", password);

        new EmployeePasswordAuthenticator().action(fixture.context);

        fixture.assertNoSuccess();
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        assertNull(fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
        assertNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
        verifyNoInteractions(fixture.otp);
    }

    /** Số bàn, số nước ngoài và chuỗi lạ bị từ chối trước tra tài khoản. */
    @ParameterizedTest @ValueSource(strings = {"", "+12025550123", "02838221234", "employee@example.com", "0912345678\nforged"})
    void rejectsInvalidPhoneWithoutLookingUpUser(String phone) {
        var fixture = new EmployeeFlowFixture();
        fixture.input.putSingle("username", phone);
        new EmployeePasswordAuthenticator().action(fixture.context);
        fixture.assertNoSuccess();
        verify(fixture.context).failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, fixture.response);
        verifyNoInteractions(fixture.users, fixture.credentials, fixture.otp);
    }

    /** Có mật khẩu đúng nhưng không có vai nhân viên thì không được sang bước SMS. */
    @Test void rejectsNonEmployeeEvenWithCorrectPassword() {
        var fixture = new EmployeeFlowFixture();
        fixture.input.putSingle("username", "0912345678");
        fixture.input.putSingle("password", "FakePassword-Only-For-Test");
        when(fixture.credentials.isValid(any(CredentialInput[].class))).thenReturn(true);
        when(fixture.user.hasRole(fixture.employeeRole)).thenReturn(false);
        new EmployeePasswordAuthenticator().action(fixture.context);
        fixture.assertNoSuccess();
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        verify(fixture.context).failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, fixture.response);
        verifyNoInteractions(fixture.otp);
    }

    /** User có sẵn từ SSO vẫn phải nhìn thấy form và nhập lại mật khẩu. */
    @Test void alwaysChallengesAndClearsStaleSsoIdentity() {
        var fixture = new EmployeeFlowFixture();
        fixture.passwordVerified();
        new EmployeePasswordAuthenticator().authenticate(fixture.context);
        assertNull(fixture.context.getUser());
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        fixture.assertNoSuccess();
        verify(fixture.context).challenge(fixture.response);
        verifyNoInteractions(fixture.otp);
    }

    /** Huỷ form không để dấu xác minh lần trước tồn tại cho bước sau. */
    @Test void cancellationClearsPasswordProof() {
        var fixture = new EmployeeFlowFixture();
        fixture.passwordVerified();
        fixture.input.putSingle("cancel", "true");
        new EmployeePasswordAuthenticator().action(fixture.context);
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        verify(fixture.context).cancelLogin();
        fixture.assertNoSuccess();
        verifyNoInteractions(fixture.otp);
    }
}
