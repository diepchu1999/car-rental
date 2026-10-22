package com.carrental.keycloak.authentication;

import com.carrental.keycloak.support.KeycloakLoginBrowser;
import com.carrental.keycloak.support.KeycloakOtpContainer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** BR-120/129/809: đăng nhập qua form và PKCE thật, import chính realm local, không cài endpoint probe. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakLoginIT {
    private final KeycloakOtpContainer server = new KeycloakOtpContainer(true);

    /** PostgreSQL/Keycloak tạm dùng port ngẫu nhiên, không dùng realm hay dữ liệu local của Tech Owner. */
    @BeforeAll void start() { server.start(); }
    /** Chỉ dọn tài nguyên do lớp test này sở hữu. */
    @AfterAll void close() { server.close(); }

    /** Khách mới cần OTP và checkbox; đăng nhập lại dạng 0/+84 giữ cùng user và bằng chứng đồng ý. */
    @Test void createsCustomerOnlyAfterConsentAndKeepsEvidenceOnReturn() throws Exception {
        String phone = "+84912340001";
        try (var browser = browser()) {
            browser.start("customer-web");
            browser.assertForm("#car-rental-phone-form");
            browser.submit(Map.of("action", "send", "phone", "0912340001"));
            String challenge = browser.challengeId();
            assertNull(customer(phone), "Sending an OTP must not create an account.");
            verifySms(browser);
            browser.assertForm("#car-rental-terms-form");
            assertNull(customer(phone), "Verifying an OTP alone must not create an account.");
            assertEquals("local-test-v1", browser.document().getElementById("terms-version").text());
            assertTrue(browser.document().getElementById("terms-text").text().startsWith("ĐIỀU KHOẢN THỬ"));
            assertFalse(browser.document().selectFirst("input[name=termsAccepted]").hasAttr("checked"));
            browser.submit(Map.of("action", "accept"));
            browser.assertForm("#car-rental-terms-form");
            assertNull(customer(phone));
            acceptTerms(browser);
            var claims = KeycloakLoginBrowser.claims(browser.exchange());
            assertRoles(claims, "CUSTOMER");
            assertEquals(phone, claims.path("phone_number").asText());
            assertEquals("local-test-v1", claims.path("terms_version").asText());
            Customer first = customer(phone);
            assertNotNull(first);
            assertEquals(first.id(), claims.path("sub").asText());
            assertEquals(first.consentedAt(), claims.path("consented_at").asText());
            assertDoesNotThrow(() -> Instant.parse(first.consentedAt()));

            ageSend(challenge);
            browser.start("customer-web");
            browser.assertForm("#car-rental-phone-form");
            browser.submit(Map.of("action", "send", "phone", phone));
            verifySms(browser);
            var returning = KeycloakLoginBrowser.claims(browser.exchange());
            assertEquals(first, customer(phone));
            assertEquals(first.id(), returning.path("sub").asText());
            assertEquals(first.consentedAt(), returning.path("consented_at").asText());
        }
    }

    /** Mã sai và gửi lại sớm đều không cấp code; challenge cũ vẫn dùng được, tài khoản chưa sinh. */
    @Test void wrongOtpAndEarlyResendCannotCompleteCustomerLogin() throws Exception {
        String phone = "+84912340002";
        try (var browser = browser()) {
            browser.start("customer-web");
            browser.submit(Map.of("action", "send", "phone", phone));
            String challenge = browser.challengeId();
            String correct = server.code(challenge);
            String wrong = correct.equals("000000") ? "000001" : "000000";
            browser.submit(Map.of("action", "verify", "otp", wrong));
            browser.assertForm("#car-rental-sms-form");
            browser.submit(Map.of("action", "resend"));
            browser.assertForm("#car-rental-sms-form");
            assertEquals(challenge, browser.challengeId());
            assertNull(customer(phone));
            browser.submit(Map.of("action", "verify", "otp", correct));
            browser.assertForm("#car-rental-terms-form");
            browser.submit(Map.of("action", "cancel"));
            assertFalse(browser.hasCode());
            assertEquals("access_denied", browser.authorizationError());
            assertNull(customer(phone));
        }
    }

    /** Thiếu/sai mật khẩu, thiếu SMS, mật khẩu ngắn hoặc dùng lại mật khẩu hiện tại đều bị chặn. */
    @Test void employeeNeedsBothFactorsAndMustReplaceTemporaryPassword() throws Exception {
        String phone = "+84910000002";
        String temporary = server.loginSecret("CAR_RENTAL_LOCAL_STAFF_PASSWORD");
        try (var browser = browser()) {
            browser.start("admin-web");
            browser.assertForm("input[name=password]");
            browser.submit(Map.of("username", phone));
            browser.assertForm("input[name=password]");
            browser.submit(Map.of("username", phone, "password", "wrong-password"));
            browser.assertForm("input[name=password]");
            assertNull(browser.document().getElementById("sms-challenge-id"));
            browser.submit(Map.of("username", phone, "password", temporary));
            browser.assertForm("#car-rental-sms-form");
            browser.submit(Map.of("action", "verify", "otp", ""));
            browser.assertForm("#car-rental-sms-form");
            verifySms(browser);
            browser.assertForm("input[name=password-new]");
            assertTrue(customer(phone).passwordUpdateRequired());
            changePassword(browser, "short");
            browser.assertForm("input[name=password-new]");
            changePassword(browser, temporary);
            browser.assertForm("input[name=password-new]");
            changePassword(browser, replacementPassword());
            assertRoles(KeycloakLoginBrowser.claims(browser.exchange()), "BRANCH_STAFF");
            assertFalse(customer(phone).passwordUpdateRequired(), "Successful update must remove the standard action.");
        }
    }

    /** Nhân viên là khách dùng cùng user, nhưng token OTP khách không có vai nhân viên hay quyền quản trị. */
    @Test void customerTokenDoesNotLeakEmployeeRolesOrBypassEmployeeLogin() throws Exception {
        String phone = "+84910000004";
        try (var browser = browser()) {
            browser.start("admin-web");
            browser.submit(Map.of("username", phone, "password", server.loginSecret("CAR_RENTAL_LOCAL_MANAGER_B_PASSWORD")));
            String employeeChallenge = browser.challengeId();
            verifySms(browser);
            changePassword(browser, replacementPassword());
            var employee = KeycloakLoginBrowser.claims(browser.exchange());
            assertRoles(employee, "BRANCH_MANAGER");
            ageSend(employeeChallenge);

            browser.start("customer-web");
            browser.submit(Map.of("action", "send", "phone", phone));
            verifySms(browser);
            acceptTerms(browser);
            var customer = KeycloakLoginBrowser.claims(browser.exchange());
            assertEquals(customer(phone).id(), employee.path("sub").asText());
            assertEquals(employee.path("sub").asText(), customer.path("sub").asText());
            assertRoles(customer, "CUSTOMER");
            assertFalse(customer.has("resource_access"), "Customer token must not expose realm management roles.");
            browser.start("admin-web");
            browser.assertForm("input[name=password]");
        }
    }

    /** OTP khách không được mở required action đổi mật khẩu tạm của nhân viên, kể cả gửi qua client khách. */
    @Test void customerOtpCannotReplaceEmployeeTemporaryPassword() throws Exception {
        String phone = "+84910000003";
        try (var browser = browser()) {
            String originalId = customer(phone).id();
            browser.start("customer-web");
            browser.submit(Map.of("action", "send", "phone", phone));
            String challenge = browser.challengeId();
            verifySms(browser);
            acceptTerms(browser);
            assertFalse(browser.hasCode());
            assertEquals("access_denied", browser.authorizationError());
            assertNull(browser.document().selectFirst("input[name=password-new]"));
            assertEquals(originalId, customer(phone).id());
            assertTrue(customer(phone).passwordUpdateRequired());

            ageSend(challenge);
            browser.start("admin-web");
            browser.submit(Map.of("username", phone, "password", server.loginSecret("CAR_RENTAL_LOCAL_MANAGER_A_PASSWORD")));
            verifySms(browser);
            browser.assertForm("input[name=password-new]");
            changePassword(browser, replacementPassword());
            assertRoles(KeycloakLoginBrowser.claims(browser.exchange()), "BRANCH_MANAGER");
        }
    }

    /** Hai phiên cùng đồng ý không tạo trùng user, không trả lỗi, không ghi đè bằng chứng đồng ý đầu tiên. */
    @Test void concurrentConsentCreatesOneCustomerAndBothLoginsSucceed() throws Exception {
        String phone = "+84912340003";
        try (var first = browser(); var second = browser(); var executor = Executors.newFixedThreadPool(2)) {
            first.start("customer-web");
            first.submit(Map.of("action", "send", "phone", phone));
            String challenge = first.challengeId();
            verifySms(first);
            first.assertForm("#car-rental-terms-form");
            ageSend(challenge);
            second.start("customer-web");
            second.submit(Map.of("action", "send", "phone", phone));
            verifySms(second);
            second.assertForm("#car-rental-terms-form");
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = executor.submit(() -> acceptTogether(first, ready, go));
            var b = executor.submit(() -> acceptTogether(second, ready, go));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            var left = a.get(60, TimeUnit.SECONDS);
            var right = b.get(60, TimeUnit.SECONDS);
            Customer saved = customer(phone);
            assertNotNull(saved);
            for (var result : new JsonNode[] {left, right}) {
                assertEquals(saved.id(), result.path("sub").asText());
                assertEquals(saved.termsVersion(), result.path("terms_version").asText());
                assertEquals(saved.consentedAt(), result.path("consented_at").asText());
                assertRoles(result, "CUSTOMER");
            }
        }
    }

    /** Cả ba public client đều yêu cầu S256 và từ chối password grant, không dùng ROPC để lấy token test. */
    @ParameterizedTest @ValueSource(strings = {"customer-web", "admin-web", "mobile"})
    void publicClientsRejectMissingPkceAndPasswordGrant(String client) throws Exception {
        try (var browser = browser()) {
            browser.start(client, Map.of("code_challenge", "", "code_challenge_method", ""));
            assertFalse(browser.hasCode());
            assertTrue(browser.status() == 400 || "invalid_request".equals(browser.authorizationError()),
                    "Missing PKCE must be rejected by the authorization endpoint.");
            var result = browser.token(Map.of("grant_type", "password", "client_id", client,
                    "username", "+84910000001", "password", server.loginSecret("CAR_RENTAL_LOCAL_ADMIN_PASSWORD")));
            assertEquals(400, result.statusCode());
            assertEquals("unauthorized_client", KeycloakLoginBrowser.json(result).path("error").asText());
        }
    }

    /** Có authorization code hợp lệ vẫn không đổi được token khi verifier không khớp challenge. */
    @Test void mobileAuthorizationCodeRequiresMatchingVerifier() throws Exception {
        try (var browser = browser()) {
            browser.start("mobile");
            browser.submit(Map.of("action", "send", "phone", "+84912340004"));
            verifySms(browser);
            acceptTerms(browser);
            var result = browser.exchangeUsing("x".repeat(64));
            assertEquals(400, result.statusCode());
            assertEquals("invalid_grant", KeycloakLoginBrowser.json(result).path("error").asText());
        }
    }

    /** Client kỹ thuật được đọc người dùng nhưng không có quyền tạo tài khoản hoặc thay đổi vai. */
    @Test void technicalClientCanReadButCannotCreateUsers() throws Exception {
        try (var browser = browser()) {
            var response = browser.token(Map.of("grant_type", "client_credentials", "client_id", "identity-reader",
                    "client_secret", server.loginSecret("CAR_RENTAL_KEYCLOAK_READER_SECRET")));
            assertEquals(200, response.statusCode());
            var tokens = KeycloakLoginBrowser.json(response);
            var roles = KeycloakLoginBrowser.claims(tokens).path("resource_access").path("realm-management").path("roles");
            var actual = new HashSet<String>();
            roles.forEach(role -> actual.add(role.asText()));
            // Keycloak 26.7.2: view-users là composite gồm query-users và query-groups.
            // So tập đầy đủ, không nới sang containsAll để vô tình bỏ qua quyền ghi phát sinh.
            assertEquals(Set.of("view-users", "query-users", "query-groups"), actual);
            String token = tokens.path("access_token").asText();
            assertEquals(200, browser.management("GET", "users?max=1", token, "").statusCode());
            assertEquals(403, browser.management("POST", "users", token,
                    "{\"username\":\"+84912349999\",\"enabled\":true}").statusCode());
            assertNull(customer("+84912349999"));
        }
    }

    /** Hai luồng chờ cùng một cổng xuất phát, timeout hữu hạn; không mock UserProvider hay transaction. */
    private JsonNode acceptTogether(KeycloakLoginBrowser browser, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        assertTrue(go.await(15, TimeUnit.SECONDS));
        acceptTerms(browser);
        return KeycloakLoginBrowser.claims(browser.exchange());
    }
    /** Cookie jar riêng cho mỗi trình duyệt, nhưng dùng cùng realm/PG thật. */
    private KeycloakLoginBrowser browser() { return new KeycloakLoginBrowser(server.baseUri()); }
    /** Mã lấy từ log adapter giả; request vẫn đi qua form, auth session và kho OTP thật. */
    private void verifySms(KeycloakLoginBrowser browser) throws Exception {
        browser.assertForm("#car-rental-sms-form");
        browser.submit(Map.of("action", "verify", "otp", server.code(browser.challengeId())));
    }
    /** Không tự bỏ qua màn hình điều khoản: luôn kiểm form trước khi gửi checkbox đã chọn. */
    private void acceptTerms(KeycloakLoginBrowser browser) throws Exception {
        browser.assertForm("#car-rental-terms-form");
        browser.submit(Map.of("action", "accept", "termsAccepted", "on"));
    }
    /** Gửi đúng tên trường của required action chuẩn Keycloak; policy được thực thi ở server. */
    private void changePassword(KeycloakLoginBrowser browser, String password) throws Exception {
        browser.assertForm("input[name=password-new]");
        browser.submit(Map.of("password-new", password, "password-confirm", password));
    }
    /** Mật khẩu giả độc lập mỗi test, không ghi vào file realm hoặc log. */
    private String replacementPassword() { return "Replacement-" + UUID.randomUUID(); }
    /** So tập quyền chính xác để phát hiện vai nhân viên rò qua scope của client khách. */
    private void assertRoles(JsonNode claims, String expected) {
        var roles = new HashSet<String>();
        claims.path("realm_access").path("roles").forEach(role -> roles.add(role.asText()));
        assertEquals(Set.of(expected), roles);
    }
    /** Lùi sự kiện gửi giả 61 giây trong container test, không tắt hoặc rút ngắn chính sách BR-129. */
    private void ageSend(String challenge) throws Exception {
        try (var connection = server.connection(); var statement = connection.prepareStatement(sql("/sql/otp/age_sends.sql"))) {
            statement.setInt(1, 61); statement.setString(2, challenge);
            assertTrue(statement.executeUpdate() > 0);
        }
    }
    /** Đọc độc lập sau commit và khẳng định không có dòng thứ hai cho cùng số điện thoại. */
    private Customer customer(String phone) throws Exception {
        try (var connection = server.connection(); var statement = connection.prepareStatement(sql("/sql/identity/find_customer.sql"))) {
            statement.setString(1, phone);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                var customer = new Customer(rows.getString("id"), rows.getString("terms_version"),
                        rows.getString("consented_at"), rows.getBoolean("password_update_required"));
                assertFalse(rows.next(), "One normalized phone must identify exactly one account.");
                return customer;
            }
        }
    }
    /** SQL test ở resource để cùng dùng được công cụ kiểm và không nhúng câu SQL vào code Java. */
    private String sql(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing test SQL: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    /** Ảnh chụp trạng thái đã commit; không chứa OTP hay mật khẩu. */
    private record Customer(String id, String termsVersion, String consentedAt, boolean passwordUpdateRequired) { }
}
