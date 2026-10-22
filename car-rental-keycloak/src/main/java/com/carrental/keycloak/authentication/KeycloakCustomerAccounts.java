package com.carrental.keycloak.authentication;

import com.carrental.keycloak.otp.OtpSecrets;
import com.carrental.keycloak.phone.VietnamMobileNumber;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/** BR-120/809: ghi qua UserProvider, trong transaction riêng dùng connection sẵn có của Keycloak. */
final class KeycloakCustomerAccounts implements CustomerAccounts {
    static final String TERMS_VERSION = "terms_version";
    static final String CONSENTED_AT = "consented_at";
    private static final String LOCK_SQL = loadLockSql();
    private final KeycloakSession parent;
    private final OtpSecrets secrets;

    /** Không mở pool JDBC thứ hai; secret chỉ dùng tạo khoá tranh chấp không lộ số điện thoại. */
    KeycloakCustomerAccounts(KeycloakSession parent, OtpSecrets secrets) {
        this.parent = parent;
        this.secrets = secrets;
    }

    /** Session riêng tránh giữ kết quả tra cứu cũ trong phiên đang chờ khách đọc điều khoản. */
    @Override public Optional<String> findConsented(String realmId, String phone) {
        String normalized = VietnamMobileNumber.normalize(phone);
        return KeycloakModelUtils.runJobInTransactionWithResult(parent.getKeycloakSessionFactory(),
                session -> findConsentedInSession(session, realmId, normalized));
    }

    /** Khoá theo realm/số trước mọi lần đọc-ghi để hai tab không ghi đè bằng chứng đồng ý đầu tiên. */
    @Override public String accept(String realmId, String phone, String termsVersion, Instant consentedAt) {
        String normalized = VietnamMobileNumber.normalize(phone);
        long lockKey = Long.parseUnsignedLong(secrets.digest("customer-registration", realmId, normalized).substring(0, 16), 16);
        return KeycloakModelUtils.runJobInTransactionWithResult(parent.getKeycloakSessionFactory(), session -> {
            session.getProvider(JpaConnectionProvider.class).getEntityManager().unwrap(Session.class).doWork(connection -> {
                try (var statement = connection.prepareStatement(LOCK_SQL)) {
                    statement.setLong(1, lockKey);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("Customer registration lock is missing.");
                    }
                }
            });
            return acceptInSession(session, realmId, normalized, termsVersion, consentedAt);
        });
    }

    /** Chỉ khách đang bật, có vai và đủ bằng chứng đồng ý mới được bỏ qua màn hình điều khoản. */
    static Optional<String> findConsentedInSession(KeycloakSession session, String realmId, String phone) {
        RealmModel realm = requireRealm(session, realmId);
        UserModel user = session.users().getUserByUsername(realm, phone);
        if (user == null) return Optional.empty();
        requireEnabled(user);
        var role = realm.getRole("CUSTOMER");
        if (role == null) throw new IllegalStateException("CUSTOMER realm role is missing.");
        return hasConsent(user) && user.hasRole(role) ? Optional.of(user.getId()) : Optional.empty();
    }

    /** Được gọi sau khoá transaction; chỉ cấp CUSTOMER, không tự thêm vai mặc định hay vai nhân viên. */
    static String acceptInSession(KeycloakSession session, String realmId, String phone, String version, Instant at) {
        if (version == null || version.isBlank() || at == null) throw new IllegalArgumentException("Consent evidence is required.");
        RealmModel realm = requireRealm(session, realmId);
        var role = realm.getRole("CUSTOMER");
        if (role == null) throw new IllegalStateException("CUSTOMER realm role is missing.");
        UserModel user = session.users().getUserByUsername(realm, phone);
        if (user == null) {
            user = session.users().addUser(realm, UUID.randomUUID().toString(), phone, false, false);
            user.setEnabled(true);
        } else {
            requireEnabled(user);
        }
        if (!hasConsent(user)) {
            user.setSingleAttribute(TERMS_VERSION, version);
            user.setSingleAttribute(CONSENTED_AT, at.toString());
        }
        if (!user.hasRole(role)) user.grantRole(role);
        return user.getId();
    }

    /** Không tự chữa bằng chứng thiếu nửa hoặc sai định dạng bằng phiên bản/thời điểm hiện tại. */
    private static boolean hasConsent(UserModel user) {
        String version = user.getFirstAttribute(TERMS_VERSION);
        String at = user.getFirstAttribute(CONSENTED_AT);
        if (version == null && at == null) return false;
        if (version == null || version.isBlank() || at == null) throw new IllegalStateException("Customer consent evidence is incomplete.");
        try { Instant.parse(at); }
        catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalStateException("Customer consent timestamp is invalid.");
        }
        return true;
    }

    /** Tra realm bằng ID nội bộ, không lấy realm khác từ input HTTP. */
    private static RealmModel requireRealm(KeycloakSession session, String realmId) {
        RealmModel realm = session.realms().getRealm(realmId);
        if (realm == null) throw new IllegalStateException("Customer authentication realm is missing.");
        session.getContext().setRealm(realm);
        return realm;
    }

    /** Không tự bật lại tài khoản đã bị quản trị viên khoá. */
    private static void requireEnabled(UserModel user) {
        if (!user.isEnabled()) throw new CustomerAccountUnavailableException();
    }

    /** SQL nằm trong resource; khoá tự nhả khi transaction do Keycloak quản lý kết thúc. */
    private static String loadLockSql() {
        try (var input = KeycloakCustomerAccounts.class.getResourceAsStream("/sql/car-rental-identity/lock_registration.sql")) {
            if (input == null) throw new IllegalStateException("Customer registration SQL is missing.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load customer registration SQL.", failure); }
    }
}
