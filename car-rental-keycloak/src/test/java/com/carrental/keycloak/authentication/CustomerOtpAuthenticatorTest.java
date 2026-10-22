package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpIssue;
import com.carrental.keycloak.otp.OtpResult;
import com.carrental.keycloak.otp.OtpScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.models.RoleModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-120/129: kiểm cửa OTP và đồng ý điều khoản, không giả rằng unit test đã chứng minh token thật. */
class CustomerOtpAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private EmployeeFlowFixture fixture;
    private CustomerAccounts accounts;
    private CustomerOtpAuthenticator authenticator;
    private LocalTermsDocument terms;
    private String previousProfile;

    /** Dùng cấu trúc phiên chung nhưng client/vai là khách; mọi dữ liệu đều giả và đồng hồ cố định. */
    @BeforeEach void setUp() {
        previousProfile = System.getProperty("kc.profile");
        System.setProperty("kc.profile", "dev");
        fixture = new EmployeeFlowFixture();
        accounts = mock(CustomerAccounts.class);
        terms = LocalTermsDocument.load("local-test-v1");
        authenticator = new CustomerOtpAuthenticator(terms, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
        when(fixture.client.getId()).thenReturn("customer-client");
        var role = mock(RoleModel.class);
        when(fixture.realm.getRole("CUSTOMER")).thenReturn(role);
        when(fixture.user.hasRole(role)).thenReturn(true);
        when(fixture.users.getUserById(fixture.realm, "customer-id")).thenReturn(fixture.user);
        when(accounts.findConsented(anyString(), anyString())).thenReturn(Optional.empty());
        when(accounts.accept(anyString(), anyString(), anyString(), any())).thenReturn("customer-id");
        when(fixture.otp.issue(any(), anyString())).thenReturn(new OtpIssue(OtpResult.SENT, "customer-challenge", NOW.plusSeconds(300)));
        when(fixture.otp.verify(any(), anyString(), anyString(), any())).thenReturn(OtpResult.VERIFIED);
    }

    /** Không để chế độ dev của test này ảnh hưởng các test chặn SMS/văn bản thử ở chế độ thường. */
    @AfterEach void restoreProfile() {
        if (previousProfile == null) System.clearProperty("kc.profile");
        else System.setProperty("kc.profile", previousProfile);
    }

    /** Mở trang chỉ hỏi số; không gửi mã hoặc tra cứu tài khoản và xoá dấu nhân viên từ SSO. */
    @Test void startsWithPhoneWithoutLookingUpOrCreatingAccount() {
        fixture.passwordVerified();
        authenticator.authenticate(fixture.context);
        verify(fixture.forms).createForm("car-rental-phone.ftl");
        verifyNoInteractions(accounts, fixture.users, fixture.otp);
        assertNull(fixture.context.getUser());
        assertNull(fixture.notes.get(EmployeeLoginState.PASSWORD_VERIFIED));
        fixture.assertNoSuccess();
    }

    /** Hai cách nhập số gửi tới cùng đích E.164, scope khách và IP peer; vẫn chưa tìm hoặc tạo user. */
    @ParameterizedTest @ValueSource(strings = {"0912345678", "+84912345678"})
    void sendsOtpWithNormalizedPhoneAndCustomerScope(String phone) {
        send(phone);
        verify(fixture.otp).issue(argThat(scope -> scope.phoneNumber().equals("+84912345678")
                && scope.purpose() == OtpScope.Purpose.CUSTOMER_LOGIN
                && scope.authenticationSessionId().contains("customer-client")), eq("192.0.2.10"));
        verifyNoInteractions(accounts, fixture.users);
        fixture.assertNoSuccess();
    }

    /** Số bàn/nước ngoài/chuỗi rỗng không gây SMS và không chạm kho tài khoản. */
    @ParameterizedTest @ValueSource(strings = {"", "02838221234", "+12025550123"})
    void invalidPhoneDoesNotSendSms(String phone) {
        send(phone);
        verifyNoInteractions(accounts, fixture.users, fixture.otp);
        verify(fixture.forms).setError("carRentalPhoneInvalid");
        fixture.assertNoSuccess();
    }

    /** Đúng OTP chỉ hiện điều khoản; gửi thêm phone/challenge/version giả không được dùng. */
    @Test void verifiedOtpShowsTermsWithoutCreatingUser() {
        send("0912345678");
        fixture.input.putSingle("phone", "+84987654321");
        fixture.input.putSingle("challengeId", "forged");
        verifyOtp();
        verify(fixture.otp).verify(argThat(scope -> scope.phoneNumber().equals("+84912345678")),
                eq("192.0.2.10"), eq("customer-challenge"), eq("123456"));
        verify(fixture.forms).createForm("car-rental-terms.ftl");
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(fixture.users);
        fixture.assertNoSuccess();
    }

    /** Bấm đồng ý sau OTP ghi phiên bản thật và thời điểm máy chủ, không tin dữ liệu form. */
    @Test void explicitConsentCreatesUserUsingServerEvidence() {
        send("0912345678"); verifyOtp();
        fixture.input.putSingle("termsVersion", "forged-version");
        fixture.input.putSingle("consentedAt", "1900-01-01T00:00:00Z");
        fixture.input.putSingle("phone", "+84987654321");
        accept(true);
        verify(accounts).accept("car-rental-realm", "+84912345678", "local-test-v1", NOW);
        verify(fixture.context).success();
        assertSame(fixture.user, fixture.context.getUser());
        assertNull(fixture.notes.get(CustomerOtpAuthenticator.VERIFIED));
        assertNull(fixture.notes.get(EmployeeLoginState.MFA_VERIFIED));
    }

    /** Thiếu checkbox không tạo tài khoản dù OTP đã đúng. */
    @Test void refusesConsentWithoutCheckbox() {
        send("0912345678"); verifyOtp(); accept(false);
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        verify(fixture.forms).setError("carRentalTermsRequired");
        fixture.assertNoSuccess();
    }

    /** Tự gửi accept hoặc boolean verified từ client không bỏ qua bước xác minh mã. */
    @Test void refusesConsentBeforeOtpVerification() {
        send("0912345678");
        fixture.input.putSingle(CustomerOtpAuthenticator.VERIFIED, "true");
        accept(true);
        verifyNoInteractions(accounts, fixture.users);
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        fixture.assertNoSuccess();
    }

    /** Tất cả kết quả mã không hợp lệ đều không mở điều khoản hay truy cập kho tài khoản. */
    @ParameterizedTest @EnumSource(value = OtpResult.class, mode = EnumSource.Mode.EXCLUDE, names = "VERIFIED")
    void refusesEveryNonVerifiedResult(OtpResult result) {
        send("0912345678");
        when(fixture.otp.verify(any(), anyString(), anyString(), any())).thenReturn(result);
        verifyOtp();
        verifyNoInteractions(accounts, fixture.users);
        assertNull(fixture.notes.get(CustomerOtpAuthenticator.VERIFIED));
        fixture.assertNoSuccess();
    }

    /** Khách cũ đi tiếp sau OTP, không ghi lại đồng ý khi cấu hình đã đổi phiên bản. */
    @Test void existingCustomerDoesNotConsentAgain() {
        when(accounts.findConsented("car-rental-realm", "+84912345678")).thenReturn(Optional.of("customer-id"));
        authenticator = new CustomerOtpAuthenticator(LocalTermsDocument.load("local-test-v2"), accounts, Clock.fixed(NOW, ZoneOffset.UTC));
        send("0912345678"); verifyOtp();
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        verify(fixture.forms, never()).createForm("car-rental-terms.ftl");
        verify(fixture.context).success();
    }

    /** Văn bản hoặc phiên bản đổi khi đang đọc phải hiện lại, không gán bản mới cho lần đồng ý bản cũ. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void redisplaysChangedTermsBeforeAccepting(boolean versionChanged) {
        send("0912345678"); verifyOtp();
        var replacement = new LocalTermsDocument(versionChanged ? "local-test-v2" : terms.version(),
                versionChanged ? terms.text() : terms.text() + "\nVăn bản thử được cập nhật.");
        authenticator = new CustomerOtpAuthenticator(replacement, accounts, Clock.fixed(NOW, ZoneOffset.UTC));
        accept(true);
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        verify(fixture.forms).setError("carRentalTermsChanged");
        fixture.assertNoSuccess();
        accept(true);
        verify(accounts).accept("car-rental-realm", "+84912345678", replacement.version(), NOW);
        verify(fixture.context).success();
    }

    /** Chuyển tab/client sau xác minh làm mất hiệu lực dấu OTP trước khi ghi đồng ý. */
    @ParameterizedTest @ValueSource(strings = {"client", "tab"})
    void rejectsConsentFromDifferentSessionScope(String component) {
        send("0912345678"); verifyOtp();
        if (component.equals("client")) when(fixture.client.getId()).thenReturn("different-client");
        else when(fixture.auth.getTabId()).thenReturn("different-tab");
        accept(true);
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        fixture.assertNoSuccess();
    }

    /** Đổi số bằng bước send bắt đầu lại OTP, không dùng dấu xác minh của số cũ. */
    @Test void changingPhoneRequiresNewVerification() {
        send("0912345678"); verifyOtp(); send("0987654321"); accept(true);
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        fixture.assertNoSuccess();
    }

    /** Từ chối điều khoản không tạo user và xoá trạng thái xác minh. */
    @Test void decliningTermsCancelsWithoutRegistration() {
        send("0912345678"); verifyOtp();
        fixture.input.putSingle("action", "cancel");
        authenticator.action(fixture.context);
        verify(fixture.context).cancelLogin();
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        assertNull(fixture.notes.get(CustomerOtpAuthenticator.PHONE));
        fixture.assertNoSuccess();
    }

    /** Replay form đồng ý sau thành công không được gọi register lần nữa. */
    @Test void replayedConsentCannotRegisterTwice() {
        send("0912345678"); verifyOtp(); accept(true); accept(true);
        verify(accounts, times(1)).accept(anyString(), anyString(), anyString(), any());
        verify(fixture.context, times(1)).success();
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
    }

    /** Bị hạn mức gửi lại chặn vẫn giữ challenge trước đó; không chạm tài khoản. */
    @Test void rateLimitedResendKeepsPreviousChallenge() {
        send("0912345678");
        when(fixture.otp.issue(any(), anyString())).thenReturn(OtpIssue.rejected(OtpResult.RESEND_TOO_SOON));
        fixture.input.putSingle("action", "resend");
        authenticator.action(fixture.context);
        assertEquals("customer-challenge", fixture.notes.get(CustomerOtpAuthenticator.CHALLENGE));
        verifyNoInteractions(accounts, fixture.users);
        fixture.assertNoSuccess();
    }

    /** User đã khoá không được đăng nhập dù OTP đúng. */
    @Test void disabledCustomerIsRejected() {
        send("0912345678");
        when(accounts.findConsented(anyString(), anyString())).thenThrow(new CustomerAccountUnavailableException());
        verifyOtp();
        verify(fixture.context).failure(AuthenticationFlowError.ACCESS_DENIED, fixture.response);
        verify(accounts, never()).accept(anyString(), anyString(), anyString(), any());
        fixture.assertNoSuccess();
    }

    /** Gửi số qua chính action của authenticator, không đặt sẵn dấu OTP để làm test xanh giả. */
    private void send(String phone) {
        fixture.input.putSingle("action", "send"); fixture.input.putSingle("phone", phone);
        authenticator.action(fixture.context);
    }

    /** Đi qua bước xác minh dùng challenge đã được send tạo trong auth note. */
    private void verifyOtp() {
        fixture.input.putSingle("action", "verify"); fixture.input.putSingle("otp", "123456");
        authenticator.action(fixture.context);
    }

    /** Mô phỏng checkbox thật; thiếu checkbox không được coi như đã đồng ý. */
    private void accept(boolean checked) {
        fixture.input.putSingle("action", "accept");
        if (checked) fixture.input.putSingle("termsAccepted", "on"); else fixture.input.remove("termsAccepted");
        authenticator.action(fixture.context);
    }
}
