package com.carrental.keycloak.authentication;

import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.requiredactions.UpdatePassword;
import org.keycloak.models.KeycloakSession;

/** BR-809: không cho OTP khách đổi mật khẩu của tài khoản đồng thời là nhân viên. */
public final class EmployeeUpdatePassword extends UpdatePassword {
    static final String REQUIRE_EMPLOYEE_MFA = "car-rental.employee-mfa-required";
    /** Giữ ID chuẩn: Keycloak xoá required action theo factory ID sau khi đổi mật khẩu thành công. */
    @Override public String getId() { return "UPDATE_PASSWORD"; }
    /** Ưu tiên hơn factory mặc định, nhưng chỉ thêm guard ở realm chủ động bật cấu hình bên dưới. */
    @Override public int order() { return 1; }
    /** Không trả instance lớp cha vì sẽ làm mất kiểm tra MFA. */
    @Override public RequiredActionProvider create(KeycloakSession session) { return new EmployeeUpdatePassword(); }
    /** Nhãn cho quản trị viên thấy đây là action đã được bảo vệ. */
    @Override public String getDisplayText() { return "Employee password update after password and SMS"; }
    /** Kiểm trước khi cho nhìn thấy form, kể cả action do client yêu cầu qua kc_action. */
    @Override public void requiredActionChallenge(RequiredActionContext context) {
        if (requireMfa(context)) super.requiredActionChallenge(context);
    }
    /** Kiểm lại trên POST; không cho gửi thẳng form để vượt bước xác thực. */
    @Override public void processAction(RequiredActionContext context) {
        if (requireMfa(context)) super.processAction(context);
    }
    /** Mật khẩu và SMS phải cùng user/realm/root/client/tab/số; thiếu dấu thì dừng, không xoá action. */
    private boolean requireMfa(RequiredActionContext context) {
        var settings = context.getRealm().getRequiredActionProviderByAlias(getId());
        // Master và realm khác vẫn dùng hành vi gốc; realm car-rental bật guard ngay trong file import.
        if (settings == null || !"true".equals(settings.getConfig().get(REQUIRE_EMPLOYEE_MFA))) return true;
        var auth = context.getAuthenticationSession();
        if (EmployeeLoginState.isEligible(context.getRealm(), context.getUser())) {
            String binding = EmployeeLoginState.binding(context.getRealm(), auth, context.getUser());
            if (binding.equals(auth.getAuthNote(EmployeeLoginState.PASSWORD_VERIFIED))
                    && binding.equals(auth.getAuthNote(EmployeeLoginState.MFA_VERIFIED))) return true;
        }
        context.failure("carRentalEmployeePasswordUpdateDenied");
        return false;
    }
}
