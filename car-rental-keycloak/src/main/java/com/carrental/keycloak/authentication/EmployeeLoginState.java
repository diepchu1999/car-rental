package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpScope;
import com.carrental.keycloak.phone.VietnamMobileNumber;
import java.util.List;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/** BR-809: dấu xác minh chỉ sống trong phiên đăng nhập hiện tại, không lấy từ form hay SSO cookie. */
final class EmployeeLoginState {
    static final String PASSWORD_VERIFIED = "car-rental.employee.password-verified";
    static final String CHALLENGE_ID = "car-rental.employee.challenge-id";
    static final String MFA_VERIFIED = "car-rental.employee.mfa-verified";
    private static final List<String> EMPLOYEE_ROLES = List.of(
            "BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER",
            "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN");

    /** Chỉ dùng các thao tác không giữ trạng thái giữa request. */
    private EmployeeLoginState() { }

    /** BR-801/809: chỉ tài khoản đang bật với vai nhân viên giai đoạn 1 mới vào luồng này. */
    static boolean isEligible(AuthenticationFlowContext context) {
        return isEligible(context.getRealm(), context.getUser());
    }

    /** Cùng điều kiện áp dụng khi Keycloak chuyển từ flow đăng nhập sang required action. */
    static boolean isEligible(RealmModel realm, UserModel user) {
        if (user == null || !user.isEnabled()) return false;
        try {
            if (!VietnamMobileNumber.normalize(user.getUsername()).equals(user.getUsername())) return false;
        } catch (IllegalArgumentException invalidPhone) {
            return false;
        }
        return EMPLOYEE_ROLES.stream().map(realm::getRole)
                .anyMatch(role -> role != null && user.hasRole(role));
    }

    /** Ghép có độ dài để không nhập nhằng; mã OTP không dùng lại được ở client/tab khác. */
    static OtpScope scope(AuthenticationFlowContext context) {
        var auth = context.getAuthenticationSession();
        String session = part(auth.getParentSession().getId())
                + part(auth.getClient().getId()) + part(auth.getTabId());
        return new OtpScope(context.getRealm().getId(), session,
                context.getUser().getUsername(), OtpScope.Purpose.EMPLOYEE_LOGIN);
    }

    /** Dấu mật khẩu còn gắn với user và số hiện tại; đổi tài khoản hoặc số phải xác minh lại. */
    static String binding(AuthenticationFlowContext context) {
        return binding(context.getRealm(), context.getAuthenticationSession(), context.getUser());
    }

    /** Giữ cùng biểu diễn khi kiểm ở trang đổi mật khẩu; không lấy bằng chứng từ user session cũ. */
    static String binding(RealmModel realm, AuthenticationSessionModel auth, UserModel user) {
        String session = part(auth.getParentSession().getId()) + part(auth.getClient().getId()) + part(auth.getTabId());
        return part(realm.getId()) + part(session) + part(user.getId())
                + part(VietnamMobileNumber.normalize(user.getUsername()));
    }

    /** Thiếu dấu hoặc mất quyền giữa hai bước đều không được gửi/xác minh SMS. */
    static boolean hasVerifiedPassword(AuthenticationFlowContext context) {
        return isEligible(context) && binding(context).equals(
                context.getAuthenticationSession().getAuthNote(PASSWORD_VERIFIED));
    }

    /** Bắt đầu hoặc thử lại mật khẩu thì xoá dấu của lần trước, kể cả khi lần mới thất bại. */
    static void clear(AuthenticationFlowContext context) {
        var auth = context.getAuthenticationSession();
        auth.removeAuthNote(PASSWORD_VERIFIED);
        auth.removeAuthNote(CHALLENGE_ID);
        auth.removeAuthNote(MFA_VERIFIED);
    }

    /** Từ chối cấu hình phiên thiếu thành phần thay vì ràng buộc vào một chuỗi rỗng. */
    private static String part(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Employee authentication scope is incomplete.");
        return value.length() + ":" + value;
    }
}
