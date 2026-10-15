package com.carrental.keycloak.probe;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Fixture chỉ đóng trong test JAR: gọi SPI thật trong vòng đời transaction HTTP thật của Keycloak. */
public final class OtpProbeFactory implements RealmResourceProviderFactory {
    /** Constructor cho ServiceLoader của container test. */
    public OtpProbeFactory() { }
    /** Không phải endpoint đăng nhập; fixture không thể cấp token hay tạo user. */
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            /** Tạo resource giữ session/transaction hiện tại. */
            @Override public Object getResource() { return new OtpProbeResource(session); }
            /** Session do Keycloak đóng. */
            @Override public void close() { }
        };
    }
    /** Không nạp cấu hình nghiệp vụ trong fixture. */
    @Override public void init(Config.Scope config) { }
    /** Không can thiệp cấu hình server. */
    @Override public void postInit(KeycloakSessionFactory factory) { }
    /** Không giữ tài nguyên riêng. */
    @Override public void close() { }
    /** Đường fixture chỉ tồn tại khi cài test JAR, không có trong JAR triển khai. */
    @Override public String getId() { return "otp-probe"; }
}
