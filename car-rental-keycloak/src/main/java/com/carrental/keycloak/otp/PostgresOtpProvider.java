package com.carrental.keycloak.otp;

import org.hibernate.Session;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.utils.KeycloakModelUtils;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Mượn kết nối hiện có trong session/transaction riêng do Keycloak quản lý, không tự commit JDBC. */
final class PostgresOtpProvider implements OtpProvider {
    private final KeycloakSession parent;
    private final OtpPolicy policy;
    private final OtpSecrets secrets;
    private final SmsSender sender;

    /** Nhận session đang xử lý đăng nhập; chỉ dùng factory của nó cho giao dịch OTP độc lập. */
    PostgresOtpProvider(KeycloakSession parent, OtpPolicy policy, OtpSecrets secrets, SmsSender sender) {
        this.parent = parent; this.policy = policy; this.secrets = secrets; this.sender = sender;
    }

    /** Commit hạn mức và challenge trước khi gửi; lỗi vận chuyển không làm sống lại mã cũ/hạn mức. */
    @Override public OtpIssue issue(OtpScope scope, String remoteAddress) {
        String id = UUID.randomUUID().toString();
        String code = secrets.generate(policy.digits());
        OtpIssue result = transaction(connection -> new OtpStore(connection, policy, secrets)
                .issue(scope, remoteAddress, id, code));
        if (result.result() == OtpResult.SENT) sender.send(scope.phoneNumber(), id, code);
        return result;
    }

    /** Mọi kết quả từ chối có chủ đích là giá trị trả về để Keycloak commit bộ đếm sai. */
    @Override public OtpResult verify(OtpScope scope, String remoteAddress, String challengeId, String code) {
        return transaction(connection -> new OtpStore(connection, policy, secrets)
                .verify(scope, remoteAddress, challengeId, code));
    }

    /** runJob tạo transaction REQUIRES_NEW; Hibernate chỉ cho mượn connection đang thuộc transaction đó. */
    private <T> T transaction(JdbcWork<T> work) {
        return KeycloakModelUtils.runJobInTransactionWithResult(parent.getKeycloakSessionFactory(), session ->
                session.getProvider(JpaConnectionProvider.class).getEntityManager().unwrap(Session.class)
                        .doReturningWork(work::run));
    }

    /** Không đóng connection/session của bên cung cấp. */
    @Override public void close() { }

    /** Callback JDBC nội bộ, không đưa SQL vào authenticator. */
    private interface JdbcWork<T> {
        /** Thực hiện một thao tác kho bằng connection của Keycloak. */
        T run(Connection connection) throws SQLException;
    }
}
