package com.carrental.identity.adapter.out.keycloak;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.identity.application.port.out.ReadAccountRolesPort;
import com.carrental.identity.config.KeycloakReaderSettings;
import com.carrental.identity.domain.AccountRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Kiểm HTTP thực sự do adapter dựng, nhưng transport được chặn trong bộ nhớ, không gọi Keycloak local. */
class KeycloakAccountRolesReadAdapterTest {
    private static final String BASE = "http://keycloak.invalid";
    private static final String TOKEN_PATH = BASE + "/realms/car-rental/protocol/openid-connect/token";
    private static final String USER_PATH = BASE + "/admin/realms/car-rental/users/employee-subject";
    private static final String ROLE_PATH = USER_PATH + "/role-mappings/realm/composite";
    private static final String SECRET = "test-only-secret+&";
    private static final String TOKEN = "test-only-service-token";
    private final KeycloakReaderSettings settings = new KeycloakReaderSettings(BASE, "car-rental", "identity-reader", SECRET);
    private MockRestServiceServer server;
    private KeycloakAccountRolesReadAdapter adapter;

    /** Gắn transport giả trước khi build RestClient, không mock cổng hoặc logic phân tích vai. */
    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new KeycloakAccountRolesReadAdapter(settings, builder.build());
    }

    /** Mỗi nhánh phải gọi đủ và đúng chuỗi request dự kiến, không có request ghi hoặc retry ngầm. */
    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    /** Cả 12 vai đều được nhận diện chính xác; vai hệ thống không tự trở thành quyền nghiệp vụ. */
    @ParameterizedTest
    @EnumSource(AccountRole.class)
    void readsEachProjectRoleFromEffectiveRealmRoles(AccountRole role) {
        expectToken();
        expectUser();
        expectRoles(withSuccess("[{\"name\":\"" + role.name() + "\",\"clientRole\":false},"
                + "{\"name\":\"offline_access\",\"clientRole\":false}]", MediaType.APPLICATION_JSON));
        assertEquals(Set.of(role), adapter.findBySubject("employee-subject").orElseThrow().values());
    }

    /** BR-809: CUSTOMER cùng một vai nhân viên hợp lệ; vai lặp không tạo ra nhân viên thứ hai. */
    @Test
    void permitsCustomerAndOneEmployeeWithDuplicateRoleEntries() {
        expectToken();
        expectUser();
        expectRoles(withSuccess("""
                [{"name":"CUSTOMER","clientRole":false},
                 {"name":"BRANCH_STAFF","clientRole":false},
                 {"name":"BRANCH_STAFF","clientRole":false}]
                """, MediaType.APPLICATION_JSON));
        assertEquals(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF),
                adapter.findBySubject("employee-subject").orElseThrow().values());
    }

    /** User có thật nhưng không có vai dự án trả Optional hiện diện với tập rỗng. */
    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{\"name\":\"default-roles-car-rental\",\"clientRole\":false}]",
            "[{\"name\":\"admin\",\"clientRole\":false}]"})
    void distinguishesExistingUserWithoutRecognizedRoles(String body) {
        expectToken();
        expectUser();
        expectRoles(withSuccess(body, MediaType.APPLICATION_JSON));
        assertTrue(adapter.findBySubject("employee-subject").orElseThrow().values().isEmpty());
    }

    /** Chỉ GET user trả 404 mới là không tồn tại; adapter không gọi tiếp endpoint vai. */
    @Test
    void returnsEmptyOnlyForMissingUser() {
        expectToken();
        expectGet(USER_PATH, withStatus(HttpStatusCode.valueOf(404)).body("private user response"));
        assertTrue(adapter.findBySubject("employee-subject").isEmpty());
    }

    /** Hai vai nhân viên là dữ liệu lỗi; không chọn ADMIN hoặc bỏ một vai để tiếp tục gán chi nhánh. */
    @Test
    void rejectsMultipleEmployeeRoles() {
        expectToken();
        expectUser();
        expectRoles(withSuccess("""
                [{"name":"ADMIN","clientRole":false},{"name":"BRANCH_STAFF","clientRole":false}]
                """, MediaType.APPLICATION_JSON));
        assertSafeFailure("multiple employee roles violate BR-809");
    }

    /** Token endpoint lỗi không bị đổi thành người dùng không tồn tại, kể cả 404 hoặc redirect. */
    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 403, 404, 429, 500})
    void rejectsTokenEndpointFailures(int status) {
        expectTokenResponse(withStatus(HttpStatusCode.valueOf(status)).body(SECRET + TOKEN));
        assertSafeFailure("token request failed: HTTP " + status);
    }

    /** Thiếu quyền hoặc lỗi máy chủ khi tra user phải dừng, không tiếp tục đọc vai. */
    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 429, 500})
    void rejectsUserLookupFailures(int status) {
        expectToken();
        expectGet(USER_PATH, withStatus(HttpStatusCode.valueOf(status)).body(SECRET + TOKEN));
        assertSafeFailure("user lookup failed: HTTP " + status);
    }

    /** Vai trả 404 sau khi đã thấy user cũng phải báo lỗi, không giả thành tập vai rỗng. */
    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 404, 429, 500})
    void rejectsRoleLookupFailures(int status) {
        expectToken();
        expectUser();
        expectRoles(withStatus(HttpStatusCode.valueOf(status)).body(SECRET + TOKEN));
        assertSafeFailure("role lookup failed: HTTP " + status);
    }

    /** Token không đúng hợp đồng bị từ chối trước khi tạo Authorization header. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"access_token\":5,\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"test-only-service-token\"}",
            "{\"access_token\":\"test-only-service-token\",\"token_type\":\"Basic\"}",
            "{\"access_token\":\"test-only-service-token\\r\\nforged\",\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"test-only-service-token\",\"token_type\":\"Bearer\"} {}"})
    void rejectsMalformedTokenResponses(String body) {
        expectTokenResponse(withSuccess(body, MediaType.APPLICATION_JSON));
        assertSafeFailure("token request failed:");
    }

    /** 200 nhưng rỗng, sai kiểu hoặc user khác không được xem là 404 hay tiếp tục cấp vai. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"id\":42}", "{\"id\":\"another-subject\"}"})
    void rejectsMalformedOrMismatchedUsers(String body) {
        expectToken();
        expectGet(USER_PATH, withSuccess(body, MediaType.APPLICATION_JSON));
        assertSafeFailure("user lookup failed:");
    }

    /** Không ép kiểu role name hoặc chấp nhận client role giả là realm role của dự án. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[null]", "[{}]", "[{\"name\":5,\"clientRole\":false}]",
            "[{\"name\":\"ADMIN\"}]", "[{\"name\":\"ADMIN\",\"clientRole\":true}]",
            "[{\"name\":\"ADMIN\",\"clientRole\":\"false\"}]", "[{\"name\":\"\",\"clientRole\":false}]"})
    void rejectsMalformedRoles(String body) {
        expectToken();
        expectUser();
        expectRoles(withSuccess(body, MediaType.APPLICATION_JSON));
        assertSafeFailure("role lookup failed:");
    }

    /** Cause của lỗi mạng có thể chứa credential; stack trace được báo không được kéo theo cause đó. */
    @Test
    void sanitizesTransportFailuresWithoutRetry() {
        expectTokenResponse(request -> { throw new IOException(SECRET + TOKEN + "+84912345678"); });
        assertSafeFailure("token request failed: transport or response decoding error");
    }

    /** JSON hỏng chứa token/PII không được xuất hiện trong exception parser đi lên handler 500. */
    @Test
    void sanitizesJsonDecodingFailures() {
        expectToken();
        expectGet(USER_PATH, withSuccess("{\"private\":\"" + SECRET + TOKEN + "+84912345678",
                MediaType.APPLICATION_JSON));
        assertSafeFailure("user lookup failed: transport or response decoding error");
    }

    /** Kể cả bật DEBUG của RestClient, form client credentials và body người dùng không được in ra log. */
    @Test
    void doesNotLogCredentialsOrPersonalDataAtDebugLevel() {
        var logger = (Logger) LoggerFactory.getLogger("org.springframework.web.client.DefaultRestClient");
        Level previousLevel = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        try {
            expectToken();
            expectUser();
            expectRoles(withSuccess("[]", MediaType.APPLICATION_JSON));
            assertTrue(adapter.findBySubject("employee-subject").orElseThrow().values().isEmpty());
            for (var event : events.list) {
                String message = event.getFormattedMessage();
                assertFalse(message.contains(SECRET));
                assertFalse(message.contains(URLEncoder.encode(SECRET, StandardCharsets.UTF_8)));
                assertFalse(message.contains(TOKEN));
                assertFalse(message.contains("+84912345678"));
            }
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** Mỗi lượt đọc lấy vai mới; không giữ vai nhân viên cũ sau khi Keycloak đổi vai. */
    @Test
    void doesNotCacheRolesAcrossCalls() {
        expectToken();
        expectUser();
        expectRoles(withSuccess("[{\"name\":\"BRANCH_STAFF\",\"clientRole\":false}]", MediaType.APPLICATION_JSON));
        expectToken();
        expectUser();
        expectRoles(withSuccess("[{\"name\":\"BRANCH_MANAGER\",\"clientRole\":false}]", MediaType.APPLICATION_JSON));
        assertEquals(Set.of(AccountRole.BRANCH_STAFF), adapter.findBySubject("employee-subject").orElseThrow().values());
        assertEquals(Set.of(AccountRole.BRANCH_MANAGER), adapter.findBySubject("employee-subject").orElseThrow().values());
    }

    /** Subject là biến URI được encode, không được nối thẳng làm đổi đường dẫn hoặc query của endpoint. */
    @Test
    void encodesSubjectAsOneUriVariable() {
        expectToken();
        expectGet(BASE + "/admin/realms/car-rental/users/user%2Fother%3Fadmin%3Dtrue",
                withStatus(HttpStatusCode.valueOf(404)));
        assertTrue(adapter.findBySubject("user/other?admin=true").isEmpty());
    }

    /** Đầu vào nội bộ sai phải dừng trước khi gọi mạng; không in lại giá trị đó trong thông báo. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "bad\r\nsubject", ".", ".."})
    void rejectsInvalidSubjectBeforeCallingKeycloak(String subject) {
        assertThrows(IllegalArgumentException.class, () -> adapter.findBySubject(subject));
    }

    /** Bean production lắp được mà không gọi mạng trong constructor, không cần secret thật cho unit test. */
    @Test
    void registersReadPortWithoutStartupNetworkCall() {
        new ApplicationContextRunner().withBean(KeycloakReaderSettings.class, () -> settings)
                .withUserConfiguration(KeycloakAccountRolesReadAdapter.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(KeycloakAccountRolesReadAdapter.class, context.getBean(ReadAccountRolesPort.class));
                });
    }

    /** Token thử chỉ tồn tại trong transport giả, không phải JWT hay credential của realm local. */
    private void expectToken() {
        expectTokenResponse(withSuccess("{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\"}",
                MediaType.APPLICATION_JSON));
    }

    /** Kiểm form client_credentials đúng, kể cả encode dấu + và & trong secret, không có header user. */
    private void expectTokenResponse(ResponseCreator response) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "identity-reader");
        form.add("client_secret", SECRET);
        server.expect(requestTo(TOKEN_PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(response);
    }

    /** User có số điện thoại trong JSON nhưng adapter chỉ cần kiểm subject, không đưa phone vào view/log. */
    private void expectUser() {
        expectGet(USER_PATH, withSuccess("{\"id\":\"employee-subject\",\"username\":\"+84912345678\"}",
                MediaType.APPLICATION_JSON));
    }

    /** Bắt buộc gọi endpoint composite thay vì chỉ lấy vai gán trực tiếp. */
    private void expectRoles(ResponseCreator response) {
        expectGet(ROLE_PATH, response);
    }

    /** Admin API chỉ có GET và token service account, không có POST/PUT gán vai hoặc tạo user. */
    private void expectGet(String uri, ResponseCreator response) {
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN)).andRespond(response);
    }

    /** Kiểm cả stack trace/cause, không chỉ message ngoài cùng vì logger lỗi sẽ in toàn bộ cause. */
    private void assertSafeFailure(String expected) {
        var failure = assertThrows(IllegalStateException.class, () -> adapter.findBySubject("employee-subject"));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
        assertNull(failure.getCause());
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(SECRET));
        assertFalse(output.toString().contains(TOKEN));
        assertFalse(output.toString().contains("+84912345678"));
        assertFalse(output.toString().contains("employee-subject"));
    }
}
