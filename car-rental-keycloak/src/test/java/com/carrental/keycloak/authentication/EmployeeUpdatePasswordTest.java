package com.carrental.keycloak.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.models.UserModel;
import org.keycloak.models.RequiredActionProviderModel;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-809: đổi mật khẩu phải giữ đủ bằng chứng mật khẩu và SMS của đúng phiên nhân viên. */
class EmployeeUpdatePasswordTest {
    private final EmployeeFlowFixture fixture = new EmployeeFlowFixture();
    private final RequiredActionContext context = mock(RequiredActionContext.class);
    private final EmployeeUpdatePassword action = new EmployeeUpdatePassword();

    /** Cùng auth note với luồng đăng nhập, nhưng dùng context riêng của required action. */
    EmployeeUpdatePasswordTest() {
        var settings = new RequiredActionProviderModel();
        settings.setConfig(Map.of(EmployeeUpdatePassword.REQUIRE_EMPLOYEE_MFA, "true"));
        when(fixture.realm.getRequiredActionProviderByAlias("UPDATE_PASSWORD")).thenReturn(settings);
        when(context.getAuthenticationSession()).thenReturn(fixture.auth);
        when(context.getRealm()).thenReturn(fixture.realm);
        when(context.getUser()).thenReturn(fixture.user);
        when(context.form()).thenReturn(fixture.forms);
        when(fixture.auth.getAuthenticatedUser()).thenReturn(fixture.user);
        when(fixture.forms.createResponse(UserModel.RequiredAction.UPDATE_PASSWORD)).thenReturn(fixture.response);
    }

    /** OTP khách hoặc chỉ mật khẩu đều không được mở form hay gửi thẳng mật khẩu mới. */
    @ParameterizedTest @ValueSource(strings = {"none", "password", "sms"})
    void rejectsIncompleteProofOnBothGetAndPost(String proof) {
        fixture.identifyEmployee();
        String binding = EmployeeLoginState.binding(fixture.context);
        if (proof.equals("password")) fixture.notes.put(EmployeeLoginState.PASSWORD_VERIFIED, binding);
        if (proof.equals("sms")) fixture.notes.put(EmployeeLoginState.MFA_VERIFIED, binding);
        action.requiredActionChallenge(context);
        action.processAction(context);
        verify(context, times(2)).failure("carRentalEmployeePasswordUpdateDenied");
        verify(context, never()).form();
        verifyNoInteractions(fixture.credentials);
    }

    /** Không chấp nhận dấu từ tab khác dù tài khoản và số điện thoại vẫn như cũ. */
    @Test void rejectsProofFromAnotherTab() {
        verified();
        when(fixture.auth.getTabId()).thenReturn("another-tab");
        action.requiredActionChallenge(context);
        action.processAction(context);
        verify(context, times(2)).failure("carRentalEmployeePasswordUpdateDenied");
        verifyNoInteractions(fixture.credentials);
    }

    /** Mất quyền hoặc bị khoá sau OTP vẫn phải bị từ chối trước khi sửa credential. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void rejectsDisabledOrNonEmployee(boolean disabled) {
        verified();
        if (disabled) when(fixture.user.isEnabled()).thenReturn(false);
        else when(fixture.user.hasRole(fixture.employeeRole)).thenReturn(false);
        action.requiredActionChallenge(context);
        action.processAction(context);
        verify(context, times(2)).failure("carRentalEmployeePasswordUpdateDenied");
        verifyNoInteractions(fixture.credentials);
    }

    /** Đủ hai bước thì dùng chính form và password policy chuẩn của Keycloak. */
    @Test void allowsChallengeAfterBothEmployeeFactors() {
        verified();
        action.requiredActionChallenge(context);
        verify(context).challenge(fixture.response);
        verify(context, never()).failure(anyString());
    }

    /** Factory giữ ID chuẩn để required action được xoá đúng và tạo lớp có guard. */
    @Test void factoryPreservesGuardAndStandardActionId() {
        assertInstanceOf(EmployeeUpdatePassword.class, action.create(fixture.session));
        assertEquals("UPDATE_PASSWORD", action.getId());
        assertTrue(action.order() > 0);
    }

    /** Master/realm không bật guard tiếp tục dùng form gốc, không bị yêu cầu vai của car-rental. */
    @Test void leavesOtherRealmsOnStandardBehavior() {
        when(fixture.realm.getRequiredActionProviderByAlias("UPDATE_PASSWORD")).thenReturn(null);
        action.requiredActionChallenge(context);
        verify(context).challenge(fixture.response);
        verify(context, never()).failure(anyString());
    }

    /** Chỉ test helper mới đặt trực tiếp bằng chứng; browser integration phải đi qua hai form thật. */
    private void verified() {
        fixture.passwordVerified();
        fixture.notes.put(EmployeeLoginState.MFA_VERIFIED, EmployeeLoginState.binding(fixture.context));
    }
}
