package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpSecrets;
import java.time.Clock;
import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Lắp luồng khách với bản điều khoản thử và kho người dùng thật của Keycloak. */
public final class CustomerOtpAuthenticatorFactory implements AuthenticatorFactory {
    private LocalTermsDocument terms;
    private OtpSecrets secrets;
    /** Định danh để client khách/mobile chọn flow riêng trong realm. */
    @Override public String getId() { return "car-rental-customer-otp"; }
    /** Các dependency gắn với session hiện tại, không chia sẻ UserModel giữa request. */
    @Override public Authenticator create(KeycloakSession session) {
        return new CustomerOtpAuthenticator(terms, new KeycloakCustomerAccounts(session, secrets), Clock.systemUTC());
    }
    /** Thiếu phiên bản hoặc secret thì lỗi rõ ngay khi khởi động. */
    @Override public void init(Config.Scope config) {
        terms = LocalTermsDocument.load(System.getenv("CAR_RENTAL_TERMS_VERSION"));
        secrets = new OtpSecrets(System.getenv("CAR_RENTAL_OTP_HMAC_SECRET"));
    }
    /** Tên kỹ thuật hiển thị ở trang quản trị flow. */
    @Override public String getDisplayType() { return "Car Rental Customer SMS and Consent"; }
    /** Không nhầm với TOTP mặc định của Keycloak. */
    @Override public String getReferenceCategory() { return "car-rental-sms"; }
    /** Văn bản và phiên bản được cấu hình tập trung, không theo từng execution. */
    @Override public boolean isConfigurable() { return false; }
    /** OTP/đồng ý điều khoản không phải bước tuỳ chọn. */
    @Override public Requirement[] getRequirementChoices() { return new Requirement[] {Requirement.REQUIRED}; }
    /** Không có bước tự thiết lập credential thay cho SMS. */
    @Override public boolean isUserSetupAllowed() { return false; }
    /** Nêu rõ thứ tự tạo tài khoản để người cấu hình không bỏ bước đồng ý. */
    @Override public String getHelpText() { return "Verifies customer SMS and records explicit terms consent before registration."; }
    /** Không thêm các giá trị mặc định ngoài BR đã chốt. */
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    /** Không tự import realm hoặc tạo tài khoản khi nạp provider. */
    @Override public void postInit(KeycloakSessionFactory factory) { }
    /** Không sở hữu connection hoặc pool riêng. */
    @Override public void close() { }
}
