package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpProvider;
import com.carrental.keycloak.otp.OtpResult;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/** BR-809/129: SMS là bước bắt buộc sau mật khẩu; mọi hạn mức và tiêu thụ mã do OtpProvider xử lý. */
public final class EmployeeSmsAuthenticator implements Authenticator {
    /** Gửi lần đầu; tải lại màn hình không tự phát thêm SMS hay thay challenge đang dùng. */
    @Override public void authenticate(AuthenticationFlowContext context) {
        if (!requirePassword(context)) return;
        context.getAuthenticationSession().removeAuthNote(EmployeeLoginState.MFA_VERIFIED);
        if (context.getAuthenticationSession().getAuthNote(EmployeeLoginState.CHALLENGE_ID) == null) {
            send(context);
        } else {
            show(context, null);
        }
    }

    /** Chỉ VERIFIED được đi tiếp; dữ liệu phone, challenge và phạm vi đều lấy từ phía máy chủ. */
    @Override public void action(AuthenticationFlowContext context) {
        if (!requirePassword(context)) return;
        context.getAuthenticationSession().removeAuthNote(EmployeeLoginState.MFA_VERIFIED);
        var form = context.getHttpRequest().getDecodedFormParameters();
        String action = form.getFirst("action");
        if ("resend".equals(action)) {
            send(context);
            return;
        }
        String challenge = context.getAuthenticationSession().getAuthNote(EmployeeLoginState.CHALLENGE_ID);
        if (!"verify".equals(action) || challenge == null) {
            show(context, "carRentalOtpInvalid");
            return;
        }
        OtpResult result = provider(context).verify(EmployeeLoginState.scope(context),
                context.getConnection().getRemoteAddr(), challenge, form.getFirst("otp"));
        if (result != OtpResult.VERIFIED) {
            show(context, message(result));
            return;
        }
        var auth = context.getAuthenticationSession();
        auth.removeAuthNote(EmployeeLoginState.CHALLENGE_ID);
        auth.setAuthNote(EmployeeLoginState.MFA_VERIFIED, EmployeeLoginState.binding(context));
        context.success();
    }

    /** Không nhận SSO user thay cho mật khẩu và không cho sửa phone giữa hai yếu tố xác thực. */
    private boolean requirePassword(AuthenticationFlowContext context) {
        if (EmployeeLoginState.hasVerifiedPassword(context)) return true;
        EmployeeLoginState.clear(context);
        context.failure(AuthenticationFlowError.ACCESS_DENIED,
                context.form().setError("carRentalEmployeeRestart").createErrorPage(Response.Status.FORBIDDEN));
        return false;
    }

    /** Gửi lại đi qua cùng kho hạn mức; khi bị chặn vẫn giữ challenge cũ để người dùng có thể nhập. */
    private void send(AuthenticationFlowContext context) {
        var issue = provider(context).issue(EmployeeLoginState.scope(context), context.getConnection().getRemoteAddr());
        if (issue.result() == OtpResult.SENT) {
            context.getAuthenticationSession().setAuthNote(EmployeeLoginState.CHALLENGE_ID, issue.challengeId());
            show(context, null);
        } else {
            show(context, message(issue.result()));
        }
    }

    /** Form dùng action URL và mã phiên do Keycloak cấp; không đưa mã OTP hay số điện thoại vào HTML. */
    private void show(AuthenticationFlowContext context, String error) {
        var form = context.form().setAttribute("smsChallengeId",
                context.getAuthenticationSession().getAuthNote(EmployeeLoginState.CHALLENGE_ID));
        if (error != null) form.setError(error);
        context.challenge(form.createForm("car-rental-sms.ftl"));
    }

    /** Lấy SPI trên session sẵn có, không mở DataSource hay giữ connection trong authenticator. */
    private OtpProvider provider(AuthenticationFlowContext context) {
        return context.getSession().getProvider(OtpProvider.class);
    }

    /** Chuyển kết quả nội bộ sang thông báo giao diện, không trả chi tiết database hoặc exception. */
    private String message(OtpResult result) {
        return switch (result) {
            case RESEND_TOO_SOON -> "carRentalOtpResendTooSoon";
            case PHONE_RATE_LIMITED, IP_RATE_LIMITED -> "carRentalOtpRateLimited";
            case EXPIRED -> "carRentalOtpExpired";
            case CONSUMED -> "carRentalOtpConsumed";
            case ATTEMPTS_EXHAUSTED -> "carRentalOtpExhausted";
            default -> "carRentalOtpInvalid";
        };
    }

    /** Keycloak phải xác định được nhân viên ở bước mật khẩu trước khi gọi bước SMS. */
    @Override public boolean requiresUser() { return true; }

    /** Không dùng cơ chế tự thiết lập TOTP để bỏ qua SMS; kiểm nhân viên diễn ra trong từng request. */
    @Override public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) { return true; }

    /** Không thay đổi required action mật khẩu tạm do ADMIN đã đặt. */
    @Override public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) { }

    /** Provider không giữ tài nguyên giữa các request. */
    @Override public void close() { }
}
