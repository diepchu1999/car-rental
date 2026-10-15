package com.carrental.keycloak.authentication;

import java.util.List;
import java.util.Set;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

/** Đăng ký form mật khẩu riêng, không thay authenticator mặc định của realm master. */
public final class EmployeePasswordAuthenticatorFactory extends UsernamePasswordFormFactory {
    /** Mã dùng trong cấu hình flow của realm car-rental. */
    @Override public String getId() { return "car-rental-employee-password"; }

    /** Không dùng factory cha có bật WebAuthn theo cấu hình session. */
    @Override public Authenticator create(KeycloakSession session) { return new EmployeePasswordAuthenticator(); }

    /** Tên kỹ thuật hiện trong trang cấu hình flow Keycloak. */
    @Override public String getDisplayType() { return "Car Rental Employee Password"; }

    /** Giải thích đây là yếu tố thứ nhất, không đủ để phát token nhân viên. */
    @Override public String getHelpText() { return "Vietnamese mobile username and password; must be followed by employee SMS."; }

    /** Không quảng bá passkey như một phương án thay thế mật khẩu. */
    @Override public Set<String> getOptionalReferenceCategories(KeycloakSession session) { return Set.of(); }

    /** Không thêm tham số nghiệp vụ vào cấu hình từng execution. */
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
}
