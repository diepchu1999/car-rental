package com.carrental.identity.adapter.out.keycloak;

import com.carrental.identity.config.KeycloakReaderSettings;
import com.carrental.identity.domain.AccountRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static com.carrental.identity.adapter.out.keycloak.KeycloakReaderTestServer.subject;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-801/809, ADR-0006: adapter production gọi Keycloak + PostgreSQL thật bằng client chỉ đọc.
 * Không mock HTTP/port, không dùng .env hay realm local; không thay thế test OTP và JWT resource server.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class KeycloakAccountRolesReadIntegrationTest {
    private final KeycloakReaderTestServer server = new KeycloakReaderTestServer();
    private final JsonMapper json = JsonMapper.builder().build();
    private KeycloakAccountRolesReadAdapter adapter;

    /** Khởi động một cặp container cho lớp test, adapter dùng HTTP client production không có transport giả. */
    @BeforeAll
    void startServer() throws Exception {
        server.start();
        adapter = new KeycloakAccountRolesReadAdapter(server.settings());
    }

    /** JUnit vẫn gọi teardown khi setup/test lỗi, dọn tài nguyên thay vì bỏ container chạy nền. */
    @AfterAll
    void stopServer() {
        server.close();
    }

    /** Vai hiệu lực trả từ Keycloak thật được map đúng cả 12 tên đã chốt trong BR-801. */
    @ParameterizedTest
    @EnumSource(AccountRole.class)
    void readsEveryProjectRoleFromRealKeycloak(AccountRole role) {
        assertEquals(Set.of(role), adapter.findBySubject(subject("role-" + role.name())).orElseThrow().values());
    }

    /** BR-809 cho phép một tài khoản vừa là CUSTOMER vừa là nhân viên. */
    @Test
    void permitsCustomerAlongsideOneEmployee() {
        assertEquals(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF),
                adapter.findBySubject(subject("customer-and-staff")).orElseThrow().values());
    }

    /** Test sẽ đỏ nếu adapter đổi endpoint composite thành endpoint chỉ đọc vai trực tiếp. */
    @Test
    void includesInheritedCompositeRole() throws Exception {
        var direct = server.management("GET", "users/" + subject("inherited-manager") + "/role-mappings/realm",
                server.readerToken(), "");
        assertEquals(200, direct.statusCode());
        boolean containsBundle = false;
        for (var role : json.readTree(direct.body())) {
            String name = role.path("name").asString();
            assertNotEquals("BRANCH_MANAGER", name, "Fixture must not directly assign the effective role.");
            containsBundle |= name.equals("test-manager-bundle");
        }
        assertTrue(containsBundle, "Composite parent must be directly assigned.");
        assertEquals(Set.of(AccountRole.BRANCH_MANAGER),
                adapter.findBySubject(subject("inherited-manager")).orElseThrow().values());
    }

    /** Không có vai dự án vẫn là user tồn tại, không phải 404 và không được cấp quyền ngầm. */
    @Test
    void distinguishesUserWithoutProjectRoles() {
        assertTrue(adapter.findBySubject(subject("without-project-role")).orElseThrow().values().isEmpty());
    }

    /** Subject thật sự không tồn tại trả Optional rỗng sau HTTP 404 từ Keycloak. */
    @Test
    void returnsEmptyForUnknownSubject() {
        assertTrue(adapter.findBySubject(subject("never-created")).isEmpty());
    }

    /** Keycloak cho phép cấu hình hai vai nhân viên nhưng backend phải từ chối theo BR-809. */
    @Test
    void rejectsConflictingEmployeeRoles() {
        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.findBySubject(subject("multiple-employees")));
        assertTrue(failure.getMessage().contains("multiple employee roles violate BR-809"));
    }

    /** Secret sai phải thất bại ở token endpoint, không được trả tài khoản rỗng hay đọc vai dự phòng. */
    @Test
    void rejectsIncorrectTechnicalClientSecret() {
        var settings = server.settings();
        var invalid = new KeycloakAccountRolesReadAdapter(new KeycloakReaderSettings(settings.baseUrl(), settings.realm(),
                settings.readerClientId(), "wrong-test-secret"));
        var failure = assertThrows(IllegalStateException.class, () -> invalid.findBySubject(subject("role-ADMIN")));
        assertTrue(failure.getMessage().contains("token request failed: HTTP 401"));
        assertFalse(failure.getMessage().contains(settings.readerSecret()));
        assertNull(failure.getCause());
    }

    /** Token service account hợp lệ nhưng không có view-users phải nhận đúng 403, không được xem như 404. */
    @Test
    void rejectsTechnicalClientWithoutReadPermission() {
        var settings = server.settings();
        var unprivileged = new KeycloakAccountRolesReadAdapter(new KeycloakReaderSettings(settings.baseUrl(), settings.realm(),
                "reader-without-permissions", settings.readerSecret()));
        var failure = assertThrows(IllegalStateException.class,
                () -> unprivileged.findBySubject(subject("role-ADMIN")));
        assertTrue(failure.getMessage().contains("user lookup failed: HTTP 403"));
    }

    /** Client dùng được để đọc nhưng không thể tạo người dùng, theo quyền nguyên gốc trong realm-export. */
    @Test
    void technicalClientCannotCreateUsers() throws Exception {
        String token = server.readerToken();
        assertEquals(200, server.management("GET", "users/" + subject("role-ADMIN"), token, "").statusCode());
        assertEquals(403, server.management("POST", "users", token,
                "{\"username\":\"must-not-be-created\",\"enabled\":true}").statusCode());
        var lookup = server.management("GET", "users?username=must-not-be-created&exact=true", token, "");
        assertEquals(200, lookup.statusCode());
        var users = json.readTree(lookup.body());
        assertTrue(users.isArray());
        assertTrue(users.isEmpty(), "Denied write must not create a user.");
    }

    /** Client chỉ đọc không được tự nâng STAFF thành ADMIN; payload vai là representation thật từ Keycloak. */
    @Test
    void technicalClientCannotAssignRoles() throws Exception {
        String token = server.readerToken();
        var mappings = server.management("GET", "users/" + subject("role-ADMIN") + "/role-mappings/realm/composite",
                token, "");
        assertEquals(200, mappings.statusCode());
        var payload = json.createArrayNode();
        for (var role : json.readTree(mappings.body())) {
            if ("ADMIN".equals(role.path("name").asString())) payload.add(role);
        }
        assertEquals(1, payload.size());
        assertEquals(403, server.management("POST", "users/" + subject("role-BRANCH_STAFF") + "/role-mappings/realm",
                token, json.writeValueAsString(payload)).statusCode());
        assertEquals(Set.of(AccountRole.BRANCH_STAFF),
                adapter.findBySubject(subject("role-BRANCH_STAFF")).orElseThrow().values());
    }
}
