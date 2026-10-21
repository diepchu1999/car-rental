package com.carrental.keycloak.authentication;

import com.carrental.keycloak.support.KeycloakLoginBrowser;
import com.carrental.keycloak.support.KeycloakOtpContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-809/129: form mật khẩu, SMS, PKCE và Admin REST chạy trên Keycloak/PostgreSQL thật.
 * Probe chỉ dịch mốc thời gian bản ghi test, không thay policy, số lần sai hoặc kết quả xác thực.
 * Mọi lượt sai đều gửi qua form; không cài probe vào compose hay JAR production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeycloakBruteForceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KeycloakOtpContainer server = new KeycloakOtpContainer(true, true);
    private KeycloakLoginBrowser admin;
    private String adminToken;
    private int sequence;
    private String phone;
    private String userId;
    private String password;

    /** ADMIN lấy token qua mật khẩu + SMS + đổi mật khẩu tạm + PKCE, không dùng password grant. */
    @BeforeAll
    void start() throws Exception {
        server.start();
        admin = browser();
        admin.start("security-admin-console");
        admin.submit(Map.of("username", "+84910000001", "password", server.loginSecret("CAR_RENTAL_LOCAL_ADMIN_PASSWORD")));
        verifySms(admin);
        admin.assertForm("input[name=password-new]");
        String replacement = "Replacement-" + UUID.randomUUID();
        admin.submit(Map.of("password-new", replacement, "password-confirm", replacement));
        adminToken = admin.exchange().path("access_token").asText();
        assertFalse(adminToken.isBlank(), "Admin login must issue a token.");
    }

    /** Mỗi test có nhân viên riêng để không lẫn bộ đếm mật khẩu, SMS hoặc điều khoản. */
    @BeforeEach
    void createEmployee() throws Exception {
        phone = "+8491990" + String.format("%04d", ++sequence);
        password = "Employee-" + UUID.randomUUID();
        String body = JSON.writeValueAsString(Map.of("username", phone, "enabled", true,
                "credentials", java.util.List.of(Map.of("type", "password", "value", password, "temporary", false))));
        assertEquals(201, admin.management("POST", "users", adminToken, body).statusCode());
        var users = management("users?exact=true&username=" + URLEncoder.encode(phone, StandardCharsets.UTF_8));
        assertEquals(1, users.size());
        userId = users.get(0).path("id").asText();
        assertFalse(userId.isBlank());
        var role = management("roles/BRANCH_STAFF");
        assertEquals(204, admin.management("POST", "users/" + userId + "/role-mappings/realm", adminToken,
                JSON.writeValueAsString(java.util.List.of(role))).statusCode());
    }

    /** Chỉ đóng tài nguyên của lớp test, không dùng realm/volume local. */
    @AfterAll
    void close() {
        if (admin != null) admin.close();
        server.close();
    }

    /** Kiểm năm giá trị được chốt và các mặc định thật của image hiện tại, không tự cấu hình lại trong test. */
    @Test
    void importedRealmKeepsApprovedLimitsAndKeycloakDefaults() throws Exception {
        var realm = management("");
        assertTrue(realm.path("bruteForceProtected").asBoolean());
        assertFalse(realm.path("permanentLockout").asBoolean(true));
        assertEquals(5, realm.path("failureFactor").asInt());
        assertEquals(900, realm.path("waitIncrementSeconds").asInt());
        assertEquals(3600, realm.path("maxFailureWaitSeconds").asInt());
        assertEquals("MULTIPLE", realm.path("bruteForceStrategy").asText());
        assertEquals(43200, realm.path("maxDeltaTimeSeconds").asInt());
        assertEquals(1000, realm.path("quickLoginCheckMilliSeconds").asInt());
        assertEquals(60, realm.path("minimumQuickLoginWaitSeconds").asInt());
        assertEquals(0, realm.path("maxTemporaryLockouts").asInt(-1));
        assertEquals(0, realm.path("maxSecondaryAuthFailures").asInt(-1));
    }

    /** Năm lần sai khoá 900 giây; lần sáu đúng vẫn không đến SMS; ADMIN mở sớm rồi MFA thành công. */
    @Test
    void fifthFailureBlocksCorrectSixthAttemptUntilAdminUnlocks() throws Exception {
        failPasswords(5);
        assertLockDuration(900);
        assertEnabled();
        assertCorrectPasswordBlocked();
        assertEquals(5, failures().path("numFailures").asInt());
        // Cùng thao tác với nút Temporarily locked trong Admin Console 26.7.2.
        assertEquals(204, admin.management("PUT", "users/" + userId, adminToken, "{\"enabled\":true}").statusCode());
        awaitFailures(0);
        completeEmployeeLogin();
        awaitFailures(0);
    }

    /** 25 form sai thật, chỉ dịch thời gian giữa các lượt; mức khoá tăng 900/1800/2700 rồi dừng ở 3600. */
    @Test
    void twentyFiveFailuresNeverExceedOneHourOrPermanentlyDisableAccount() throws Exception {
        for (int attempt = 1; attempt <= 25; attempt++) {
            if (attempt > 1) ageFailure(2);
            wrongPassword();
            awaitFailures(attempt);
            if (attempt >= 5) assertLockDuration(Math.min(3600, (attempt / 5) * 900));
            else assertFalse(failures().path("disabled").asBoolean());
            assertEnabled();
        }
        assertCorrectPasswordBlocked();
        assertEquals(25, failures().path("numFailures").asInt());
    }

    /** Đăng nhập đầy đủ thành công xoá bộ đếm; lần sai tiếp theo được tính lại từ một. */
    @Test
    void successfulEmployeeLoginResetsConsecutiveFailures() throws Exception {
        failPasswords(4);
        completeEmployeeLogin();
        awaitFailures(0);
        wrongPassword();
        awaitFailures(1);
        assertFalse(failures().path("disabled").asBoolean());
    }

    /** Quá cửa sổ 12 giờ, cache Keycloak hết hạn bản ghi; lần sai mới được tính từ một. */
    @Test
    void failureAfterDefaultResetWindowStartsAtOne() throws Exception {
        failPasswords(5);
        var before = failures();
        assertEquals(5, before.path("numFailures").asInt());
        assertLockDuration(900);
        assertEquals(43200, management("").path("maxDeltaTimeSeconds").asInt());
        long target = requestFailureAge(43201);
        assertTrue(before.path("lastFailure").asLong() - target >= 43201_000L,
                "The target timestamp must cross the default failure reset window.");

        // Khác dịch ngắn: bản ghi đã hết TTL, không còn timestamp/bộ đếm để giữ lại.
        var expired = failures();
        for (String field : java.util.List.of("numFailures", "numTemporaryLockouts", "numSecondaryAuthFailures",
                "lastFailure", "failedLoginNotBefore")) {
            assertTrue(expired.path(field).isIntegralNumber(), "Missing expired failure field: " + field);
            assertEquals(0L, expired.path(field).asLong(), "Expiration must reset " + field);
        }
        assertTrue(expired.path("disabled").isBoolean());
        assertFalse(expired.path("disabled").asBoolean());
        assertEnabled();
        wrongPassword();
        awaitFailures(1);
        assertFalse(failures().path("disabled").asBoolean());
    }

    /** Năm OTP sai của nhân viên chỉ chạm BR-129, không tăng bốn lỗi mật khẩu đã có lên năm. */
    @Test
    void employeeSmsFailuresUseOtpLimitsNotPasswordCounter() throws Exception {
        failPasswords(4);
        try (var employee = browser()) {
            employee.start("admin-web");
            employee.submit(Map.of("username", phone, "password", password));
            String correct = server.code(employee.challengeId());
            String wrong = correct.equals("000000") ? "000001" : "000000";
            for (int attempt = 0; attempt < 5; attempt++) {
                employee.submit(Map.of("action", "verify", "otp", wrong));
                employee.assertForm("#car-rental-sms-form");
            }
            employee.submit(Map.of("action", "verify", "otp", correct));
            employee.assertForm("#car-rental-sms-form");
            assertFalse(employee.hasCode());
        }
        assertEquals(4, failures().path("numFailures").asInt());
        assertFalse(failures().path("disabled").asBoolean());
    }

    /** Khách dùng cùng tài khoản vẫn OTP được khi mật khẩu bị khoá; không xoá hoặc vượt khoá nhân viên. */
    @Test
    void customerOtpRemainsIndependentOfEmployeePasswordLock() throws Exception {
        failPasswords(5);
        try (var customer = browser()) {
            customer.start("customer-web");
            customer.submit(Map.of("action", "send", "phone", phone));
            String correct = server.code(customer.challengeId());
            customer.submit(Map.of("action", "verify", "otp", correct.equals("000000") ? "000001" : "000000"));
            customer.assertForm("#car-rental-sms-form");
            assertEquals(5, failures().path("numFailures").asInt());
            customer.submit(Map.of("action", "verify", "otp", correct));
            customer.assertForm("#car-rental-terms-form");
            customer.submit(Map.of("action", "accept", "termsAccepted", "on"));
            var claims = KeycloakLoginBrowser.claims(customer.exchange());
            assertEquals(userId, claims.path("sub").asText());
            var roles = claims.path("realm_access").path("roles");
            assertEquals(1, roles.size());
            assertEquals("CUSTOMER", roles.get(0).asText());
        }
        assertLockDuration(900);
        assertEquals(5, failures().path("numFailures").asInt());
        assertCorrectPasswordBlocked();
    }

    /** Tạo n lỗi qua form; tránh quick-login bằng dịch bản ghi sau khi đã quan sát commit lần trước. */
    private void failPasswords(int count) throws Exception {
        for (int attempt = 1; attempt <= count; attempt++) {
            if (attempt > 1) ageFailure(2);
            wrongPassword();
            awaitFailures(attempt);
        }
    }

    /** Mỗi lần thử có phiên form mới, mật khẩu sai không được tới bước SMS. */
    private void wrongPassword() throws Exception {
        try (var employee = browser()) {
            employee.start("admin-web");
            employee.submit(Map.of("username", phone, "password", "Wrong-" + UUID.randomUUID()));
            employee.assertForm("input[name=password]");
            assertNull(employee.document().getElementById("sms-challenge-id"));
        }
    }

    /** Bị khoá là từ chối ngay yếu tố mật khẩu, không phải phát SMS rồi từ chối ở bước sau. */
    private void assertCorrectPasswordBlocked() throws Exception {
        try (var employee = browser()) {
            employee.start("admin-web");
            employee.submit(Map.of("username", phone, "password", password));
            employee.assertForm("input[name=password]");
            assertNull(employee.document().getElementById("sms-challenge-id"));
            assertFalse(employee.hasCode());
        }
    }

    /** Hoàn tất cả hai yếu tố và đổi code, không coi mật khẩu đúng một mình là đăng nhập thành công. */
    private void completeEmployeeLogin() throws Exception {
        try (var employee = browser()) {
            employee.start("admin-web");
            employee.submit(Map.of("username", phone, "password", password));
            verifySms(employee);
            assertEquals(userId, KeycloakLoginBrowser.claims(employee.exchange()).path("sub").asText());
        }
    }

    /** Dùng code từ SMS giả của chính challenge; không in mật khẩu, OTP hoặc token vào assertion. */
    private void verifySms(KeycloakLoginBrowser browser) throws Exception {
        browser.assertForm("#car-rental-sms-form");
        browser.submit(Map.of("action", "verify", "otp", server.code(browser.challengeId())));
    }

    /** Đợi worker brute-force commit với hạn 10 giây, không cho một test lỗi chờ vô hạn. */
    private void awaitFailures(int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int actual;
        do {
            actual = failures().path("numFailures").asInt(-1);
            if (actual == expected) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual, "Password failure counter did not reach the expected committed value.");
    }

    /** So mốc do server lưu, không đo đồng hồ máy host hoặc chờ thật 15–60 phút. */
    private void assertLockDuration(int seconds) throws Exception {
        var failure = failures();
        assertTrue(failure.path("disabled").asBoolean());
        long duration = failure.path("failedLoginNotBefore").asLong() - failure.path("lastFailure").asLong() / 1000;
        assertEquals(seconds, duration);
        assertTrue(duration <= 3600, "Temporary lock must not exceed one hour.");
    }

    /** enabled vẫn true phân biệt khoá tạm với khoá vĩnh viễn trên user. */
    private void assertEnabled() throws Exception { assertTrue(management("users/" + userId).path("enabled").asBoolean()); }
    /** Đọc trạng thái qua Admin REST thực tế, không suy khoá từ câu chữ trên HTML. */
    private JsonNode failures() throws Exception { return management(failurePath()); }
    /** Đường quản trị chuẩn để quan sát bộ đếm, không phải endpoint ứng dụng mới. */
    private String failurePath() { return "attack-detection/brute-force/users/" + userId; }
    /**
     * Đọc qua hai HTTP request độc lập trước/sau probe: thời gian phải đổi thật sau commit,
     * không chỉ nhận HTTP 200; cả ba bộ đếm và IP phải giữ nguyên. Không in IP vào assertion.
     */
    private void ageFailure(int seconds) throws Exception {
        var before = failures();
        assertTrue(before.path("numFailures").asInt() > 0, "A real failed login must precede aging.");
        long expectedLastFailure = requestFailureAge(seconds);
        var after = failures();
        assertEquals(expectedLastFailure, after.path("lastFailure").asLong(),
                "Aging must persist the earlier timestamp before the next login attempt.");
        assertTrue(before.path("lastFailure").asLong() - expectedLastFailure >= seconds * 1000L,
                "The committed failure timestamp must move back by at least the requested interval.");
        for (String counter : java.util.List.of("numFailures", "numTemporaryLockouts", "numSecondaryAuthFailures")) {
            assertTrue(before.path(counter).isIntegralNumber() && after.path(counter).isIntegralNumber(),
                    "Missing failure counter: " + counter);
            assertEquals(before.path(counter).asInt(), after.path(counter).asInt(),
                    "Aging must preserve " + counter);
        }
        assertTrue(before.path("lastIPFailure").equals(after.path("lastIPFailure")), "Aging must preserve the recorded IP.");
        assertFalse(after.path("disabled").asBoolean(), "The aged temporary lock must have expired.");
        // Admin REST trả 0 cho failedLoginNotBefore khi không còn bị khoá tạm.
        assertEquals(0, after.path("failedLoginNotBefore").asLong());
    }

    /** Chỉ gửi yêu cầu dịch thời gian; từng test tự kiểm hậu điều kiện giữ bộ đếm hoặc hết hạn. */
    private long requestFailureAge(int seconds) throws Exception {
        String[] result = server.callInRealm("car-rental", "age-password-failure",
                Map.of("userId", userId, "ageSeconds", Integer.toString(seconds))).split("\\|", -1);
        assertEquals(2, result.length, "Aging probe must return its target timestamp.");
        assertEquals("aged", result[0]);
        return Long.parseLong(result[1]);
    }
    /** Yêu cầu quản trị phải thành công thật; không đưa body nhạy cảm vào báo lỗi. */
    private JsonNode management(String path) throws Exception {
        var response = admin.management("GET", path, adminToken, "");
        assertEquals(200, response.statusCode(), "Admin read failed.");
        return KeycloakLoginBrowser.json(response);
    }
    /** Cookie riêng, chỉ kết nối Keycloak container của test. */
    private KeycloakLoginBrowser browser() { return new KeycloakLoginBrowser(server.baseUri()); }
}
