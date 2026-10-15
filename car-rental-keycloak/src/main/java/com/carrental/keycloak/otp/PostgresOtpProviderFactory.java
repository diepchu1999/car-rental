package com.carrental.keycloak.otp;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import java.util.ServiceLoader;

/** Nạp cấu hình BR-129 một lần; không dựng DataSource hay chứa thông tin kết nối riêng. */
public final class PostgresOtpProviderFactory implements OtpProviderFactory {
    private OtpPolicy policy;
    private OtpSecrets secrets;
    private SmsSender sender;

    /** Constructor công khai để Keycloak tạo factory. */
    public PostgresOtpProviderFactory() { }

    /** Thiếu biến hoặc secret thì dừng khởi động, không lùi về cấu hình phỏng đoán. */
    @Override public void init(Config.Scope config) {
        policy = OtpPolicy.from(System::getenv);
        secrets = new OtpSecrets(System.getenv("CAR_RENTAL_OTP_HMAC_SECRET"));
    }

    /** Chọn đúng một adapter SMS đã được cài; JAR lõi không tự sinh adapter giả. */
    @Override public OtpProvider create(KeycloakSession session) {
        return new PostgresOtpProvider(session, policy, secrets, sender);
    }

    /** Kiểm adapter ngay khi server khởi động; adapter local tự từ chối ngoài start-dev. */
    @Override public void postInit(KeycloakSessionFactory factory) {
        var senders = ServiceLoader.load(SmsSender.class, getClass().getClassLoader()).stream().toList();
        if (senders.size() != 1) throw new IllegalStateException("Exactly one SMS sender must be installed.");
        sender = senders.getFirst().get();
    }
    /** Pool và transaction thuộc Keycloak nên factory không đóng chúng. */
    @Override public void close() { }
    /** Định danh provider mặc định của SPI. */
    @Override public String getId() { return "postgres"; }
}
