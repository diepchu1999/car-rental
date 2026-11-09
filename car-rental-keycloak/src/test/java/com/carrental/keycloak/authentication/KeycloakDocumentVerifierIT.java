package com.carrental.keycloak.authentication;

import com.carrental.keycloak.support.KeycloakLoginBrowser;
import com.carrental.keycloak.support.KeycloakOtpContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130/809: realm đã commit, Keycloak/PostgreSQL thật, nhập mật khẩu + OTP và đổi code bằng PKCE. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakDocumentVerifierIT {
    private final KeycloakOtpContainer server = new KeycloakOtpContainer(true);
    private final ObjectMapper json = new ObjectMapper();

    /** Container tạm không cài probe, không đọc .env hay chạm Keycloak local. */
    @BeforeAll void start() { server.start(); }

    /** Chỉ dọn hai container và network của test. */
    @AfterAll void close() { server.close(); }

    /** Chỉ gán vai có sẵn trong realm; nếu export/scope/SPI thiếu verifier thì đăng nhập không đạt. */
    @Test void verifierUsesEmployeeMfaAndCustomerScopeStaysSeparate() throws Exception {
        String phone = "+84912340108";
        String temporary = "Local-Only-" + UUID.randomUUID();
        try (var admin = new KeycloakLoginBrowser(server.baseUri()); var employee = new KeycloakLoginBrowser(server.baseUri())) {
            admin.start("security-admin-console");
            admin.submit(Map.of("username", "+84910000001", "password", server.loginSecret("CAR_RENTAL_LOCAL_ADMIN_PASSWORD")));
            verifySms(admin);
            admin.assertForm("input[name=password-new]");
            String replacement = "Local-Admin-" + UUID.randomUUID();
            admin.submit(Map.of("password-new", replacement, "password-confirm", replacement));
            String adminToken = admin.exchange().path("access_token").asText();
            assertFalse(adminToken.isBlank());

            var roleResponse = admin.management("GET", "roles/DOCUMENT_VERIFIER", adminToken, "");
            assertEquals(200, roleResponse.statusCode());
            var role = KeycloakLoginBrowser.json(roleResponse);
            String body = json.writeValueAsString(Map.of("username", phone, "enabled", true,
                    "credentials", List.of(Map.of("type", "password", "value", temporary, "temporary", true)),
                    "requiredActions", List.of("UPDATE_PASSWORD")));
            assertEquals(201, admin.management("POST", "users", adminToken, body).statusCode());
            var usersResponse = admin.management("GET", "users?exact=true&username=" + URLEncoder.encode(phone, StandardCharsets.UTF_8), adminToken, "");
            assertEquals(200, usersResponse.statusCode());
            var users = KeycloakLoginBrowser.json(usersResponse);
            assertEquals(1, users.size());
            String userId = users.get(0).path("id").asText();
            assertFalse(userId.isBlank());
            assertEquals(204, admin.management("POST", "users/" + userId + "/role-mappings/realm", adminToken,
                    json.writeValueAsString(List.of(role))).statusCode());

            employee.start("admin-web");
            employee.assertForm("input[name=password]");
            employee.submit(Map.of("username", phone, "password", "wrong-local-password"));
            employee.assertForm("input[name=password]");
            assertFalse(employee.hasCode());
            employee.submit(Map.of("username", phone, "password", temporary));
            employee.assertForm("#car-rental-sms-form");
            String challenge = employee.challengeId();
            String otp = server.code(challenge);
            employee.submit(Map.of("action", "verify", "otp", otp.equals("000000") ? "000001" : "000000"));
            employee.assertForm("#car-rental-sms-form");
            assertFalse(employee.hasCode());
            employee.submit(Map.of("action", "verify", "otp", otp));
            employee.assertForm("input[name=password-new]");
            assertFalse(employee.hasCode());
            String password = "Local-Verifier-" + UUID.randomUUID();
            employee.submit(Map.of("password-new", password, "password-confirm", password));
            var claims = KeycloakLoginBrowser.claims(employee.exchange());
            assertRoles(claims, "DOCUMENT_VERIFIER");
            assertEquals(userId, claims.path("sub").asText());
            assertEquals("admin-web", claims.path("azp").asText());
            assertFalse(claims.has("resource_access"));

            // Chỉ lùi sự kiện gửi trong container test, không đổi hạn mức hoặc ngủ qua cooldown.
            try (var connection = server.connection();
                 var input = getClass().getResourceAsStream("/sql/otp/age_sends.sql")) {
                assertNotNull(input);
                try (var statement = connection.prepareStatement(new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
                    statement.setInt(1, 61); statement.setString(2, challenge);
                    assertTrue(statement.executeUpdate() > 0);
                }
            }
            employee.start("customer-web");
            employee.submit(Map.of("action", "send", "phone", phone));
            verifySms(employee);
            employee.assertForm("#car-rental-terms-form");
            employee.submit(Map.of("action", "accept", "termsAccepted", "on"));
            var customer = KeycloakLoginBrowser.claims(employee.exchange());
            assertRoles(customer, "CUSTOMER");
            assertEquals(userId, customer.path("sub").asText());
            assertFalse(customer.has("resource_access"));
            employee.start("admin-web");
            employee.assertForm("input[name=password]");
        }
    }

    /** Mã chỉ lấy từ SMS giả của challenge hiện tại; không ghi OTP hoặc token ra assertion. */
    private void verifySms(KeycloakLoginBrowser browser) throws Exception {
        browser.assertForm("#car-rental-sms-form");
        browser.submit(Map.of("action", "verify", "otp", server.code(browser.challengeId())));
    }

    /** Kiểm tập vai chính xác để phát hiện vai xác minh hoặc quản trị rò sang client khách. */
    private static void assertRoles(JsonNode claims, String expected) {
        var roles = new HashSet<String>();
        claims.path("realm_access").path("roles").forEach(role -> roles.add(role.asText()));
        assertEquals(Set.of(expected), roles);
    }
}
