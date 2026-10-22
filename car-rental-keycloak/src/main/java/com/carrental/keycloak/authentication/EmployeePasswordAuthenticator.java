package com.carrental.keycloak.authentication;

import com.carrental.keycloak.phone.VietnamMobileNumber;
import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.services.messages.Messages;

/** BR-809/129: chuẩn hoá số rồi giao Keycloak kiểm mật khẩu, không có nhánh đăng nhập bằng passkey. */
public final class EmployeePasswordAuthenticator extends UsernamePasswordForm {
    /** Dùng constructor không bật WebAuthn: luồng nhân viên bắt buộc mật khẩu cộng SMS. */
    public EmployeePasswordAuthenticator() { super(); }

    /** Luôn hiện form mật khẩu, không coi tài khoản đã có trong cookie là đủ xác thực. */
    @Override public void authenticate(AuthenticationFlowContext context) {
        reset(context);
        context.challenge(context.form().setAttribute(LoginFormsProvider.REGISTRATION_DISABLED, true)
                .createLoginUsernamePassword());
    }

    /** Xoá dấu lần trước trước mọi lần submit, kể cả huỷ đăng nhập hoặc nhập sai mật khẩu. */
    @Override public void action(AuthenticationFlowContext context) {
        reset(context);
        super.action(context);
    }

    /** Chỉ đánh dấu sau khi cả mật khẩu lẫn tư cách nhân viên đều hợp lệ. */
    @Override protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> data) {
        try {
            data.putSingle("username", VietnamMobileNumber.normalize(data.getFirst("username")));
        } catch (IllegalArgumentException invalidPhone) {
            reject(context);
            return false;
        }
        if (!super.validateForm(context, data)) return false;
        if (!EmployeeLoginState.isEligible(context)) {
            reject(context);
            return false;
        }
        context.getAuthenticationSession().setAuthNote(EmployeeLoginState.PASSWORD_VERIFIED,
                EmployeeLoginState.binding(context));
        return true;
    }

    /** Không cho user hoặc trạng thái ẩn username từ luồng khác bỏ qua bước nhập số và mật khẩu. */
    private void reset(AuthenticationFlowContext context) {
        EmployeeLoginState.clear(context);
        context.getAuthenticationSession().removeAuthNote(USER_SET_BEFORE_USERNAME_PASSWORD_AUTH);
        context.clearUser();
    }

    /** Thông báo chung không tiết lộ số có tài khoản hay vai nào; không in dữ liệu nhập vào log. */
    private void reject(AuthenticationFlowContext context) {
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                context.form().setError(Messages.INVALID_USER).createLoginUsernamePassword());
    }
}
