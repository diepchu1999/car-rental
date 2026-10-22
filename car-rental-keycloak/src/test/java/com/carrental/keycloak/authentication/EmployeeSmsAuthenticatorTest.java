package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpIssue;
import com.carrental.keycloak.otp.OtpResult;
import com.carrental.keycloak.otp.OtpScope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowError;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-809/129: kiểm mọi đường đi vào SMS, không thay các test kho OTP với PostgreSQL thật. */
class EmployeeSmsAuthenticatorTest {
    /** User từ SSO, kể cả form giả có dấu mật khẩu, không đủ để gửi hoặc xác minh SMS. */
    @Test void refusesSendingAndVerifyingWithoutServerSidePasswordProof() {
        var fixture = new EmployeeFlowFixture();
        fixture.identifyEmployee();
        fixture.input.putSingle(EmployeeLoginState.PASSWORD_VERIFIED, EmployeeLoginState.binding(fixture.context));
        fixture.input.putSingle("otp", "123456");
        var authenticator = new EmployeeSmsAuthenticator();
        authenticator.authenticate(fixture.context);
        authenticator.action(fixture.context);
        verifyNoInteractions(fixture.otp);
        fixture.assertNoSuccess();
        verify(fixture.context, times(2)).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
    }

    /** Thiếu user thì từ chối rõ ràng, không dereference null hoặc gọi kho OTP. */
    @Test void refusesMissingUser() {
        var fixture = new EmployeeFlowFixture();
        new EmployeeSmsAuthenticator().authenticate(fixture.context);
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        verifyNoInteractions(fixture.otp);
        fixture.assertNoSuccess();
    }

    /** Dấu mật khẩu không được chuyển sang realm, phiên, client, tab, user hoặc số khác. */
    @ParameterizedTest @ValueSource(strings = {"realm", "root", "client", "tab", "user", "phone"})
    void rejectsChangedPasswordBinding(String component) {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        switch (component) {
            case "realm" -> when(fixture.realm.getId()).thenReturn("another-realm");
            case "root" -> when(fixture.root.getId()).thenReturn("another-root");
            case "client" -> when(fixture.client.getId()).thenReturn("another-client");
            case "tab" -> when(fixture.auth.getTabId()).thenReturn("another-tab");
            case "user" -> when(fixture.user.getId()).thenReturn("another-user");
            case "phone" -> when(fixture.user.getUsername()).thenReturn("+84987654321");
            default -> fail("Unknown test component");
        }
        new EmployeeSmsAuthenticator().action(fixture.context);
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        verifyNoInteractions(fixture.otp);
        fixture.assertNoSuccess();
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        assertNull(fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
    }

    /** Khoá user hoặc thu hồi vai sau bước mật khẩu cũng không được hoàn tất OTP. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsDisabledUserOrRevokedRole(boolean disabled) {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        if (disabled) when(fixture.user.isEnabled()).thenReturn(false);
        else when(fixture.user.hasRole(fixture.employeeRole)).thenReturn(false);
        new EmployeeSmsAuthenticator().action(fixture.context);
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        verifyNoInteractions(fixture.otp);
        fixture.assertNoSuccess();
    }

    /** Mỗi trang gửi một lần, phạm vi là EMPLOYEE_LOGIN; refresh không gửi lại và không in OTP ra HTML. */
    @Test void issuesScopedChallengeOnceAndReusesItOnRefresh() {
        var fixture = new EmployeeFlowFixture();
        fixture.passwordVerified();
        when(fixture.otp.issue(any(), anyString())).thenReturn(new OtpIssue(
                OtpResult.SENT, "server-challenge", Instant.parse("2026-10-12T03:05:00Z")));
        var authenticator = new EmployeeSmsAuthenticator();
        authenticator.authenticate(fixture.context);
        authenticator.authenticate(fixture.context);

        verify(fixture.otp).issue(argThat(scope -> scope.purpose() == OtpScope.Purpose.EMPLOYEE_LOGIN
                && scope.phoneNumber().equals("+84912345678") && scope.realmId().equals("car-rental-realm")
                && scope.authenticationSessionId().equals("12:root-session15:admin-client-id9:login-tab")), eq("192.0.2.10"));
        assertEquals("server-challenge", fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
        verify(fixture.forms, times(2)).setAttribute("smsChallengeId", "server-challenge");
        verify(fixture.forms, never()).setFormData(any());
        verify(fixture.forms, times(2)).createForm("car-rental-sms.ftl");
        fixture.assertNoSuccess();
    }

    /** Form không được ghi đè số, IP hoặc challenge; chỉ mã nhập được chuyển vào verify. */
    @Test void verifiesServerChallengeAndTrustedPeerThenMarksMfa() {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        fixture.input.putSingle("phone", "+84987654321");
        fixture.input.putSingle("challengeId", "forged-challenge");
        fixture.input.putSingle("ip", "198.51.100.5");
        when(fixture.otp.verify(any(), anyString(), anyString(), any())).thenReturn(OtpResult.VERIFIED);

        new EmployeeSmsAuthenticator().action(fixture.context);

        verify(fixture.otp).verify(EmployeeLoginState.scope(fixture.context), "192.0.2.10", "server-challenge", "123456");
        verify(fixture.request, never()).getHttpHeaders();
        assertNull(fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
        assertEquals(EmployeeLoginState.binding(fixture.context), fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
        verify(fixture.context).success();
        verifyNoInteractions(fixture.users);
        verify(fixture.user, never()).removeRequiredAction(anyString());
    }

    /** Tất cả trạng thái khác VERIFIED đều không thành công; gồm sai, hết hạn, dùng lại và quá hạn mức. */
    @ParameterizedTest @EnumSource(value = OtpResult.class, mode = EnumSource.Mode.EXCLUDE, names = "VERIFIED")
    void rejectsEveryNonVerifiedResult(OtpResult result) {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        fixture.notes.put(EmployeeLoginState.MFA_VERIFIED, "stale-proof");
        when(fixture.otp.verify(any(), anyString(), anyString(), any())).thenReturn(result);
        new EmployeeSmsAuthenticator().action(fixture.context);
        fixture.assertNoSuccess();
        assertNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
        verify(fixture.context).challenge(fixture.response);
    }

    /** Replay sau thành công không có challenge phía máy chủ nên không gọi verify lần thứ hai. */
    @Test void cannotReplaySuccessfulForm() {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        when(fixture.otp.verify(any(), anyString(), anyString(), any())).thenReturn(OtpResult.VERIFIED);
        var authenticator = new EmployeeSmsAuthenticator();
        authenticator.action(fixture.context);
        authenticator.action(fixture.context);
        verify(fixture.context, times(1)).success();
        verify(fixture.otp, times(1)).verify(any(), anyString(), anyString(), any());
        verify(fixture.context).challenge(fixture.response);
    }

    /** Gửi lại bị hạn mức chặn phải giữ mã cũ và không được lách qua verify hay đánh dấu thành công. */
    @ParameterizedTest @EnumSource(value = OtpResult.class, names = {"RESEND_TOO_SOON", "PHONE_RATE_LIMITED", "IP_RATE_LIMITED"})
    void preservesChallengeWhenResendIsDenied(OtpResult result) {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        fixture.input.putSingle("action", "resend");
        when(fixture.otp.issue(any(), anyString())).thenReturn(OtpIssue.rejected(result));
        new EmployeeSmsAuthenticator().action(fixture.context);
        assertEquals("server-challenge", fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
        verify(fixture.otp).issue(EmployeeLoginState.scope(fixture.context), "192.0.2.10");
        verify(fixture.otp, never()).verify(any(), anyString(), anyString(), any());
        fixture.assertNoSuccess();
    }

    /** Chỉ thay challenge trong phiên sau khi kho OTP đã chấp nhận phát mã mới. */
    @Test void storesNewChallengeAfterSuccessfulResend() {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        fixture.input.putSingle("action", "resend");
        when(fixture.otp.issue(any(), anyString())).thenReturn(new OtpIssue(
                OtpResult.SENT, "new-challenge", Instant.parse("2026-10-12T03:06:00Z")));
        new EmployeeSmsAuthenticator().action(fixture.context);
        assertEquals("new-challenge", fixture.notes.get(EmployeeLoginState.CHALLENGE_ID));
        fixture.assertNoSuccess();
    }

    /** Không có challenge hoặc action không hợp lệ thì không đụng kho OTP. */
    @ParameterizedTest @ValueSource(strings = {"missing-challenge", "unknown-action"})
    void rejectsMalformedForm(String scenario) {
        var fixture = new EmployeeFlowFixture();
        fixture.verificationForm();
        if (scenario.equals("missing-challenge")) fixture.notes.remove(EmployeeLoginState.CHALLENGE_ID);
        else fixture.input.putSingle("action", "bypass");
        new EmployeeSmsAuthenticator().action(fixture.context);
        verifyNoInteractions(fixture.otp);
        fixture.assertNoSuccess();
        verify(fixture.context).challenge(fixture.response);
    }
}
