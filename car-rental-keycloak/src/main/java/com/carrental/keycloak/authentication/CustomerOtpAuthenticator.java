package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpProvider;
import com.carrental.keycloak.otp.OtpResult;
import com.carrental.keycloak.otp.OtpScope;
import com.carrental.keycloak.phone.VietnamMobileNumber;
import java.time.Clock;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/** BR-120/129: chỉ tạo khách sau OTP và đồng ý điều khoản; form không được tự cung cấp bằng chứng. */
public final class CustomerOtpAuthenticator implements Authenticator {
    static final String PHONE = "car-rental.customer.phone";
    static final String CHALLENGE = "car-rental.customer.challenge";
    static final String VERIFIED = "car-rental.customer.verified";
    static final String TERMS = "car-rental.customer.terms";
    private final LocalTermsDocument terms;
    private final CustomerAccounts accounts;
    private final Clock clock;

    /** Clock phía máy chủ ghi thời điểm đồng ý; không nhận thời điểm hay phiên bản từ trình duyệt. */
    CustomerOtpAuthenticator(LocalTermsDocument terms, CustomerAccounts accounts, Clock clock) {
        this.terms = terms; this.accounts = accounts; this.clock = clock;
    }

    /** Khôi phục đúng màn hình của phiên hiện tại, không dùng SSO để bỏ qua OTP. */
    @Override public void authenticate(AuthenticationFlowContext context) {
        prepare(context);
        if (verified(context)) afterVerification(context);
        else if (note(context, PHONE) != null) sms(context, null);
        else phone(context, null);
    }

    /** Mọi action đều kiểm trạng thái máy chủ; không tin các trường user, phone hay consent tự gửi thêm. */
    @Override public void action(AuthenticationFlowContext context) {
        prepare(context);
        var form = context.getHttpRequest().getDecodedFormParameters();
        String action = form.getFirst("action");
        if ("cancel".equals(action)) { clear(context); context.cancelLogin(); return; }
        if ("send".equals(action)) {
            clear(context);
            try { context.getAuthenticationSession().setAuthNote(PHONE, VietnamMobileNumber.normalize(form.getFirst("phone"))); }
            catch (IllegalArgumentException invalid) { phone(context, "carRentalPhoneInvalid"); return; }
            send(context);
        } else if ("resend".equals(action) && note(context, PHONE) != null && !verified(context)) {
            send(context);
        } else if ("verify".equals(action) && note(context, PHONE) != null && note(context, CHALLENGE) != null) {
            verify(context, form.getFirst("otp"));
        } else if ("accept".equals(action) && verified(context)) {
            if (!terms.fingerprint().equals(note(context, TERMS))) {
                consent(context, "carRentalTermsChanged");
            } else if (!"on".equals(form.getFirst("termsAccepted"))) {
                consent(context, "carRentalTermsRequired");
            } else {
                try {
                    finish(context, accounts.accept(context.getRealm().getId(), note(context, PHONE), terms.version(), clock.instant()));
                } catch (CustomerAccountUnavailableException unavailable) { reject(context); }
            }
        } else {
            reject(context);
        }
    }

    /** Tài liệu chỉ là bản thử; luồng khách không bao giờ kế thừa dấu mật khẩu/MFA nhân viên. */
    private void prepare(AuthenticationFlowContext context) {
        terms.requireLocalRuntime();
        context.clearUser();
        EmployeeLoginState.clear(context);
    }

    /** Phát mã qua cùng SPI/hạn mức đã kiểm ở PostgreSQL thật, không tìm hoặc tạo user trước OTP. */
    private void send(AuthenticationFlowContext context) {
        var issue = context.getSession().getProvider(OtpProvider.class).issue(scope(context), context.getConnection().getRemoteAddr());
        if (issue.result() == OtpResult.SENT) {
            context.getAuthenticationSession().setAuthNote(CHALLENGE, issue.challengeId());
            sms(context, null);
        } else {
            sms(context, issue.result() == OtpResult.RESEND_TOO_SOON ? "carRentalOtpResendTooSoon" : "carRentalOtpRateLimited");
        }
    }

    /** Chỉ kết quả VERIFIED mới sinh dấu phía máy chủ và mở bước xem điều khoản. */
    private void verify(AuthenticationFlowContext context, String code) {
        OtpResult result = context.getSession().getProvider(OtpProvider.class).verify(
                scope(context), context.getConnection().getRemoteAddr(), note(context, CHALLENGE), code);
        if (result != OtpResult.VERIFIED) { sms(context, "carRentalOtpInvalid"); return; }
        context.getAuthenticationSession().removeAuthNote(CHALLENGE);
        context.getAuthenticationSession().setAuthNote(VERIFIED, binding(context));
        afterVerification(context);
    }

    /** Khách cũ đi tiếp mà không ghi lại phiên bản hoặc thời điểm đồng ý; khách mới phải đọc và chọn. */
    private void afterVerification(AuthenticationFlowContext context) {
        try {
            var existing = accounts.findConsented(context.getRealm().getId(), note(context, PHONE));
            if (existing.isPresent()) finish(context, existing.get());
            else consent(context, null);
        } catch (CustomerAccountUnavailableException unavailable) { reject(context); }
    }

    /** Chỉ context được gắn user sau khi đã xác minh và có bằng chứng đồng ý; không phát vai ở đây. */
    private void finish(AuthenticationFlowContext context, String userId) {
        UserModel user = context.getSession().users().getUserById(context.getRealm(), userId);
        var role = context.getRealm().getRole("CUSTOMER");
        if (user == null || !user.isEnabled() || !note(context, PHONE).equals(user.getUsername())
                || role == null || !user.hasRole(role)) { reject(context); return; }
        clear(context);
        context.setUser(user);
        context.success();
    }

    /** Lưu dấu văn bản thật đang hiển thị; khách không được thay phiên bản bằng hidden input. */
    private void consent(AuthenticationFlowContext context, String error) {
        context.getAuthenticationSession().setAuthNote(TERMS, terms.fingerprint());
        var form = context.form().setAttribute("termsVersion", terms.version()).setAttribute("termsText", terms.text());
        if (error != null) form.setError(error);
        context.challenge(form.createForm("car-rental-terms.ftl"));
    }

    /** Dùng action URL của Keycloak để giữ cơ chế kiểm phiên/CSRF của máy chủ. */
    private void phone(AuthenticationFlowContext context, String error) {
        var form = context.form();
        if (error != null) form.setError(error);
        context.challenge(form.createForm("car-rental-phone.ftl"));
    }

    /** Tái dùng màn hình SMS, không phản chiếu OTP hoặc số điện thoại ra HTML. */
    private void sms(AuthenticationFlowContext context, String error) {
        var form = context.form().setAttribute("smsChallengeId", note(context, CHALLENGE));
        if (error != null) form.setError(error);
        context.challenge(form.createForm("car-rental-sms.ftl"));
    }

    /** Scope gồm realm, root session, client và tab; không thể dùng mã nhân viên cho khách. */
    private OtpScope scope(AuthenticationFlowContext context) {
        var auth = context.getAuthenticationSession();
        return new OtpScope(context.getRealm().getId(), part(auth.getParentSession().getId())
                + part(auth.getClient().getId()) + part(auth.getTabId()), note(context, PHONE), OtpScope.Purpose.CUSTOMER_LOGIN);
    }

    /** Dấu xác minh chỉ có hiệu lực khi toàn bộ phạm vi hiện tại còn khớp. */
    private String binding(AuthenticationFlowContext context) {
        var scope = scope(context);
        return part(scope.realmId()) + part(scope.authenticationSessionId()) + part(scope.phoneNumber());
    }

    /** Không coi boolean do trình duyệt gửi là bằng chứng OTP. */
    private boolean verified(AuthenticationFlowContext context) {
        return note(context, PHONE) != null && binding(context).equals(note(context, VERIFIED));
    }

    /** Đọc auth note phía Keycloak, không đọc form hay user session note của lần đăng nhập trước. */
    private String note(AuthenticationFlowContext context, String key) { return context.getAuthenticationSession().getAuthNote(key); }

    /** Ghép có độ dài để các bộ ID khác nhau không tạo cùng chuỗi. */
    private String part(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Customer authentication scope is incomplete.");
        return value.length() + ":" + value;
    }

    /** Huỷ mọi bước đang dang dở, không huỷ bộ đếm OTP đã commit trong CSDL. */
    private void clear(AuthenticationFlowContext context) {
        for (String key : new String[] {PHONE, CHALLENGE, VERIFIED, TERMS}) context.getAuthenticationSession().removeAuthNote(key);
    }

    /** Lỗi chung không tiết lộ tài khoản hay nhận lại bằng chứng giả. */
    private void reject(AuthenticationFlowContext context) {
        clear(context);
        context.failure(AuthenticationFlowError.ACCESS_DENIED,
                context.form().setError("carRentalCustomerRestart").createErrorPage(Response.Status.FORBIDDEN));
    }

    /** Khách mới chưa có UserModel trước khi xác minh và đồng ý điều khoản. */
    @Override public boolean requiresUser() { return false; }
    /** Không dùng TOTP credential làm điều kiện bỏ qua SMS. */
    @Override public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) { return true; }
    /** Không sửa required action mật khẩu của tài khoản đồng thời là nhân viên. */
    @Override public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) { }
    /** Authenticator không giữ tài nguyên cần đóng. */
    @Override public void close() { }
}
