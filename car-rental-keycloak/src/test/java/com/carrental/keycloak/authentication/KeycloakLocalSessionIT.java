package com.carrental.keycloak.authentication;

import com.carrental.keycloak.support.KeycloakLoginBrowser;
import com.carrental.keycloak.support.KeycloakOtpContainer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Cấu hình local được Tech Owner duyệt: phiên 30 ngày, access ngắn hạn, refresh xoay vòng; giữ BR-129/809. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakLocalSessionIT {
    private static final int SESSION_SECONDS = 2_592_000;
    private final KeycloakOtpContainer server = new KeycloakOtpContainer(true);
    private KeycloakLoginBrowser admin;
    private String adminToken;

    /** Import JSON thật vào container tạm; ADMIN vẫn phải qua mật khẩu, OTP và đổi mật khẩu lần đầu. */
    @BeforeAll void start() throws Exception {
        server.start(); admin = new KeycloakLoginBrowser(server.baseUri());
        admin.start("security-admin-console");
        admin.submit(Map.of("username", "+84910000001", "password", server.loginSecret("CAR_RENTAL_LOCAL_ADMIN_PASSWORD")));
        verifySms(admin);
        admin.assertForm("input[name=password-new]"); String adminPassword = "Local-Session-" + UUID.randomUUID();
        admin.submit(Map.of("password-new", adminPassword, "password-confirm", adminPassword));
        adminToken = admin.exchange().path("access_token").asText(); assertFalse(adminToken.isBlank());
    }

    /** Chỉ đóng tài nguyên test; không dùng realm, secret hoặc volume local. */
    @AfterAll void close() {
        if (admin != null) admin.close(); server.close();
    }

    /** Kiểm giá trị sau import, gồm client kế thừa, access 5 phút và refresh không được dùng lại. */
    @Test void importsThirtyDaySessionsWithoutExtendingAccessTokens() throws Exception {
        var response = admin.management("GET", "", adminToken, ""); assertEquals(200, response.statusCode());
        var realm = KeycloakLoginBrowser.json(response);
        assertEquals(SESSION_SECONDS, realm.path("ssoSessionIdleTimeout").asInt());
        assertEquals(SESSION_SECONDS, realm.path("ssoSessionMaxLifespan").asInt());
        assertEquals(0, realm.path("clientSessionIdleTimeout").asInt(-1));
        assertEquals(0, realm.path("clientSessionMaxLifespan").asInt(-1));
        assertEquals(300, realm.path("accessTokenLifespan").asInt());
        assertTrue(realm.path("revokeRefreshToken").asBoolean());
        assertEquals(0, realm.path("refreshTokenMaxReuse").asInt(-1));
    }

    /** Khách đăng nhập OTP và chấp nhận điều khoản; refresh hai lần vẫn đúng subject/vai/client. */
    @Test void customerRefreshesWithoutRepeatingOtpAndRejectsOldRefreshToken() throws Exception {
        try (var browser = new KeycloakLoginBrowser(server.baseUri())) {
            browser.start("customer-web"); browser.submit(Map.of("action", "send", "phone", "+84912340567"));
            verifySms(browser); browser.assertForm("#car-rental-terms-form");
            browser.submit(Map.of("action", "accept", "termsAccepted", "on"));
            assertRefreshRotation(browser, "customer-web", "CUSTOMER", browser.exchange());
        }
    }

    /** Nhân viên vẫn qua mật khẩu + OTP trước phiên đầu; refresh không yêu cầu đăng nhập lại hoặc đổi vai. */
    @Test void employeeRefreshesWithoutRepeatingMfaAndRejectsOldRefreshToken() throws Exception {
        try (var browser = new KeycloakLoginBrowser(server.baseUri())) {
            // Tài khoản STAFF riêng, tránh cooldown SMS của ADMIN đã đăng nhập trong setup.
            browser.start("admin-web");
            browser.submit(Map.of("username", "+84910000002", "password", server.loginSecret("CAR_RENTAL_LOCAL_STAFF_PASSWORD")));
            verifySms(browser); browser.assertForm("input[name=password-new]");
            String replacement = "Local-Staff-" + UUID.randomUUID();
            browser.submit(Map.of("password-new", replacement, "password-confirm", replacement));
            assertRefreshRotation(browser, "admin-web", "BRANCH_STAFF", browser.exchange());
        }
    }

    /** Mỗi lần dùng refresh mới, access luôn ngắn hạn; dùng lại bản cũ phải invalid_grant, không in token. */
    private void assertRefreshRotation(KeycloakLoginBrowser browser, String client, String role, JsonNode original) throws Exception {
        var originalClaims = KeycloakLoginBrowser.claims(original);
        assertLifetime(original); String firstRefresh = original.path("refresh_token").asText();
        assertFalse(firstRefresh.isBlank()); var current = original;
        for (int i = 0; i < 2; i++) {
            // Keycloak 26.7.2 validateTokenReuse so iat < lastRefresh theo giây; tách mốc để kiểm stale token.
            // Không sửa clock/server hoặc nới lỗi mong đợi: token cũ vẫn phải bị từ chối sau refresh mới.
            Thread.sleep(1_100);
            var response = browser.token(Map.of("grant_type", "refresh_token", "client_id", client,
                    "refresh_token", current.path("refresh_token").asText()));
            assertEquals(200, response.statusCode(), "Refresh must succeed without another login.");
            var next = KeycloakLoginBrowser.json(response); assertLifetime(next);
            assertFalse(next.path("refresh_token").asText().equals(current.path("refresh_token").asText()), "Refresh token must rotate.");
            var claims = KeycloakLoginBrowser.claims(next);
            assertEquals(originalClaims.path("sub").asText(), claims.path("sub").asText());
            assertEquals(client, claims.path("azp").asText());
            var roles = claims.path("realm_access").path("roles");
            assertEquals(1, roles.size()); assertEquals(role, roles.get(0).asText()); current = next;
        }
        var replay = browser.token(Map.of("grant_type", "refresh_token", "client_id", client, "refresh_token", firstRefresh));
        assertEquals(400, replay.statusCode());
        assertEquals("invalid_grant", KeycloakLoginBrowser.json(replay).path("error").asText());
    }

    /** Đo expires_in thật; dung sai chỉ cho thời gian test, không phải kéo dài hạn phiên hoặc giả đồng hồ. */
    private void assertLifetime(JsonNode tokens) {
        assertTrue(tokens.path("expires_in").asInt() > 0 && tokens.path("expires_in").asInt() <= 300);
        int refreshSeconds = tokens.path("refresh_expires_in").asInt();
        assertTrue(refreshSeconds >= SESSION_SECONDS - 120 && refreshSeconds <= SESSION_SECONDS,
                "Refresh lifetime must be bounded by the approved 30-day session.");
    }

    /** OTP chỉ dùng trong bộ nhớ, lấy từ SMS mock thật của container, không ghi mã hoặc token ra báo cáo. */
    private void verifySms(KeycloakLoginBrowser browser) throws Exception {
        browser.assertForm("#car-rental-sms-form");
        browser.submit(Map.of("action", "verify", "otp", server.code(browser.challengeId())));
    }
}
