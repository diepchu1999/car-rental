package com.carrental.keycloak.authentication;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Đăng ký bước SMS bắt buộc, không có nhánh OPTIONAL/ALTERNATIVE bỏ qua yếu tố thứ hai. */
public final class EmployeeSmsAuthenticatorFactory implements AuthenticatorFactory {
    /** Mã dùng trong realm export, độc lập với OTP ứng dụng mặc định của Keycloak. */
    @Override public String getId() { return "car-rental-employee-sms"; }
    /** Mỗi instance không giữ dữ liệu riêng của người đăng nhập. */
    @Override public Authenticator create(KeycloakSession session) { return new EmployeeSmsAuthenticator(); }
    /** Tên kỹ thuật của execution trên trang quản trị. */
    @Override public String getDisplayType() { return "Car Rental Employee SMS"; }
    /** Phân biệt SMS với credential TOTP của Keycloak. */
    @Override public String getReferenceCategory() { return "car-rental-sms"; }
    /** Không cấu hình lại các hạn mức ở từng flow. */
    @Override public boolean isConfigurable() { return false; }
    /** Yếu tố thứ hai không được coi là tuỳ chọn theo BR-809. */
    @Override public Requirement[] getRequirementChoices() { return new Requirement[] {Requirement.REQUIRED}; }
    /** Không hướng người dùng sang màn hình thiết lập TOTP. */
    @Override public boolean isUserSetupAllowed() { return false; }
    /** Mô tả ràng buộc với mật khẩu cho người cấu hình realm. */
    @Override public String getHelpText() { return "Requires a verified employee password, then verifies one-time SMS through the OTP store."; }
    /** Hạn mức đã được cấu hình tập trung trong OtpProvider. */
    @Override public List<ProviderConfigProperty> getConfigProperties() { return List.of(); }
    /** Không giữ thêm tài nguyên hoặc cấu hình riêng tại factory. */
    @Override public void init(Config.Scope config) { }
    /** Không thay đổi realm hay tạo người dùng khi provider khởi động. */
    @Override public void postInit(KeycloakSessionFactory factory) { }
    /** Factory không sở hữu connection hay thread. */
    @Override public void close() { }
}
