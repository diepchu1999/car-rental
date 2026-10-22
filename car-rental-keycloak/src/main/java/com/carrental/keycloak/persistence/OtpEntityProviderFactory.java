package com.carrental.keycloak.persistence;

import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import java.util.List;

/** Khai báo bảng với cơ chế mở rộng Keycloak; đây không phải JPA repository của backend (ADR-0006). */
public final class OtpEntityProviderFactory implements JpaEntityProviderFactory {
    /** Constructor công khai để Keycloak tìm thấy dịch vụ. */
    public OtpEntityProviderFactory() { }
    /** Keycloak thêm metadata và chạy changelog bằng kết nối/migration lock sẵn có. */
    @Override public JpaEntityProvider create(KeycloakSession session) {
        return new JpaEntityProvider() {
            /** Chỉ metadata; truy vấn nghiệp vụ của SPI vẫn là file SQL với JDBC. */
            @Override public List<Class<?>> getEntities() {
                return List.of(OtpChallengeEntity.class, OtpSubjectLockEntity.class, OtpAttemptEventEntity.class);
            }
            /** Tài nguyên riêng, không trùng changelog chuẩn của Keycloak. */
            @Override public String getChangelogLocation() { return "META-INF/car-rental-otp-changelog.xml"; }
            /** Tên lịch sử migration riêng cho provider. */
            @Override public String getFactoryId() { return getId(); }
            /** Không giữ tài nguyên riêng cần đóng. */
            @Override public void close() { }
        };
    }
    /** Không có cấu hình kết nối thứ hai. */
    @Override public void init(Config.Scope config) { }
    /** Không chạy DDL thủ công ở đây; Keycloak quản lý migration. */
    @Override public void postInit(KeycloakSessionFactory factory) { }
    /** Factory không sở hữu pool. */
    @Override public void close() { }
    /** Định danh ổn định dùng cho lịch sử migration của Keycloak. */
    @Override public String getId() { return "car-rental-otp-entities"; }
}
