package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.identity.adapter.in.rest.admin.response.DocumentViewSessionResponse;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.LoggerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: HTTP/JWT/crypto/audit/PostgreSQL thật; chỉ thay cổng object, không giả use case hoặc người gọi. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentViewSessionApiIntegrationTest extends DocumentDatabaseTestSupport {
    @LocalServerPort private int port;
    @Autowired TestJwtIssuer tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @Autowired WebServerApplicationContext serverContext;
    @Autowired Environment environment;
    @MockitoBean DocumentObjectPort objects;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoSpyBean WriteAuditEventPort auditWrites;
    private String customer;
    private String verifier;
    private String ownerCode;
    private long ownerId;
    private String requestId;

    /** Đồng bộ khách qua JWT, sau đó chèn fixture đã commit để thread HTTP đọc được. */
    @BeforeEach void setUp() throws Exception {
        assertEquals(serverContext.getWebServer().getPort(), port, "HTTP test must use its own server port.");
        customer = tokens.newCustomerToken(clock); verifier = tokens.documentVerifierToken(clock);
        ownerCode = account(customer, false);
        Long id = jdbc.queryForObject(documentSql("find_account_id_by_code"), Map.of("code", ownerCode), Long.class);
        assertNotNull(id); ownerId = id; requestId = UUID.randomUUID().toString();
        when(objects.viewUrl(anyString(), any())).thenAnswer(invocation -> {
            assertEquals(Duration.ofSeconds(60), invocation.getArgument(1));
            // Mã key là fixture ngẫu nhiên; không dùng đường dẫn hoặc liên kết của CSDL local.
            return "https://local.invalid/" + invocation.<String>getArgument(0) + "?signature=fake";
        });
    }

    /** Cả ADMIN và verifier mở được hai loại hồ sơ, map đủ metadata và URL có hạn, không lộ cột mật mã. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCUMENT_VERIFIER"})
    void authorizedCallerReceivesExactPrivateResponse(String role) throws Exception {
        String bearer = role.equals("ADMIN") ? tokens.adminToken(clock) : verifier;
        String actor = account(bearer, true);
        for (String type : new String[]{"IDENTITY_CARD", "DRIVER_LICENSE"}) {
            requestId = UUID.randomUUID().toString();
            String code = prepare(type, "PENDING");
            var before = clock.instant();
            var data = success(open(bearer, code, "{}"));
            assertEquals(Set.of("code", "accountCode", "documentType", "status", "number", "fullName", "birthDate",
                    "expiresOn", "issuedOn", "licenseClass", "automaticOnly", "submittedAt", "reviewedAt", "rejectionCode",
                    "rejectionNote", "frontImage", "backImage"), keys(data));
            assertEquals(code, data.path("code").asString()); assertEquals(ownerCode, data.path("accountCode").asString());
            assertEquals(type, data.path("documentType").asString()); assertEquals("PENDING", data.path("status").asString());
            assertEquals(fakeNumber(ownerCode), data.path("number").asString());
            assertEquals("2030-01-01", data.path("expiresOn").asString());
            assertEquals(AT, data.path("submittedAt").asString());
            for (String field : new String[]{"reviewedAt", "rejectionCode", "rejectionNote"}) assertTrue(data.path(field).isNull());
            if (type.equals("IDENTITY_CARD")) {
                assertEquals("LOCAL FAKE DOCUMENT", data.path("fullName").asString());
                assertEquals("1990-01-01", data.path("birthDate").asString());
                assertTrue(data.path("issuedOn").isNull());
                assertTrue(data.path("licenseClass").isNull()); assertTrue(data.path("automaticOnly").isNull());
            } else {
                assertTrue(data.path("fullName").isNull()); assertTrue(data.path("birthDate").isNull());
                assertEquals("2025-01-01", data.path("issuedOn").asString());
                assertEquals("B", data.path("licenseClass").asString()); assertFalse(data.path("automaticOnly").asBoolean());
            }
            for (String side : new String[]{"frontImage", "backImage"}) {
                var image = data.path(side); assertEquals(Set.of("url", "expiresAt"), keys(image));
                assertTrue(image.path("url").asString().startsWith("https://local.invalid/sealed/"));
                Instant expiry = Instant.parse(image.path("expiresAt").asString());
                assertFalse(expiry.isBefore(before.plusSeconds(60)));
                assertFalse(expiry.isAfter(clock.instant().plusSeconds(60)));
            }
            assertNotEquals(data.path("frontImage").path("url"), data.path("backImage").path("url"));
            var responseDto = json.treeToValue(data, DocumentViewSessionResponse.class);
            assertEquals("DocumentViewSessionResponse[redacted]", responseDto.toString());
            assertEquals("DocumentViewImageResponse[redacted]", responseDto.frontImage().toString());
            assertAudit(code, actor, 1);
        }
        verify(objects, times(4)).viewUrl(anyString(), any());
    }

    /** Hai lần cùng requestId phải thành hai event; connection test thấy commit trước lúc ký ở thread HTTP. */
    @Test void everyHttpOpenCommitsItsOwnAuditBeforeSigning() throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING"); String actor = account(verifier, true);
        for (int count = 1; count <= 2; count++) {
            final int expected = count;
            doAnswer(invocation -> {
                assertAudit(code, actor, expected);
                return "https://local.invalid/image?signature=fake";
            }).when(objects).viewUrl(anyString(), any());
            success(open(verifier, code, null)); assertAudit(code, actor, count);
        }
        verify(auditWrites, times(2)).insert(any());
    }

    /** Bản cũ đã duyệt/từ chối vẫn mở được để đối chiếu, không chỉ cho xem trạng thái PENDING. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED", "SUPERSEDED"})
    void opensHistoricalVersion(String status) throws Exception {
        String code = prepare("IDENTITY_CARD", status);
        var data = success(open(verifier, code, null));
        assertEquals(status, data.path("status").asString());
        if (status.equals("REJECTED")) assertEquals("ILLEGIBLE", data.path("rejectionCode").asString());
        assertEquals(1, events().size());
    }

    /** Không token 401; khách là chủ và nhân viên chi nhánh vẫn 403 trước đọc/ký/audit. */
    @Test void rejectsUnauthorizedCallersBeforeSensitiveWork() throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING"); clearInvocations(reads);
        failure(open(null, code, null), 401, "UNAUTHENTICATED");
        failure(open(customer, code, null), 403, "ACCESS_DENIED");
        failure(open(tokens.branchStaffToken(clock), code, null), 403, "ACCESS_DENIED");
        verifyNoInteractions(reads, auditWrites, objects); assertTrue(events().isEmpty());
    }

    /** Body không phải nơi khai người xem/chủ/key/requestId; unknown field phải bị từ chối. */
    @ParameterizedTest @ValueSource(strings = {"actorAccountCode", "accountCode", "requestId", "objectKey", "role"})
    void rejectsClientSuppliedContext(String field) throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING"); clearInvocations(reads);
        failure(open(verifier, code, json.writeValueAsString(Map.of(field, "LOCAL-FAKE"))), 400, "INVALID_REQUEST");
        verifyNoInteractions(reads, objects, auditWrites); assertTrue(events().isEmpty());
    }

    /** Mã GT sai trả 400; mã đúng định dạng không tồn tại trả 404, không phát URL hoặc audit mở. */
    @Test void distinguishesInvalidAndMissingDocument() throws Exception {
        failure(open(verifier, "123", null), 400, "INVALID_REQUEST");
        failure(open(verifier, "GT-ZZZZZZ", null), 404, "DOCUMENT_NOT_FOUND");
        verifyNoInteractions(objects, auditWrites); assertTrue(events().isEmpty());
    }

    /** ID thô không an toàn được filter thay; audit dùng chính ID an toàn trả về response. */
    @ParameterizedTest @ValueSource(strings = {"unsafe value", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void recordsNormalizedRequestId(String unsafe) throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING"); requestId = unsafe;
        var response = open(verifier, code, null); success(response);
        String normalized = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertNotEquals(unsafe, normalized); assertTrue(events().isEmpty());
        requestId = normalized; assertEquals(1, events().size());
    }

    /** GET không được vô tình mở hồ sơ nhạy cảm mà không ghi audit. */
    @Test void getCannotOpenViewSession() throws Exception {
        var response = send(verifier, path(prepare("IDENTITY_CARD", "PENDING")), "GET", null);
        assertEquals(405, response.statusCode()); verifyNoInteractions(objects, auditWrites); assertTrue(events().isEmpty());
    }

    /** INSERT audit đã chạy nhưng lỗi tại commit: response 500 kín, không cấp URL, không để event nửa vời. */
    @Test void failedAuditCommitReturnsSafe500WithoutLinks() throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** Fault injection tại đúng ranh giới commit, không thay audit thành no-op. */
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("Forced commit failure."); }
            });
            return null;
        }).when(auditWrites).insert(any());
        safeFailure(open(verifier, code, null));
        verify(auditWrites).insert(any()); verifyNoInteractions(objects); assertTrue(events().isEmpty());
    }

    /** Lỗi storage sau commit vẫn giữ một audit, không trả nửa response kèm thông tin giấy tờ. */
    @Test void failedSigningKeepsAuditAndHidesPartialResponse() throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING");
        doThrow(new IllegalStateException("Forced storage failure.")).when(objects).viewUrl(anyString(), any());
        safeFailure(open(verifier, code, null)); assertEquals(1, events().size());
        verify(auditWrites).insert(any()); verify(objects).viewUrl(anyString(), any());
    }

    /** Kể cả sql-log và TRACE JDBC, lượt HTTP thật không ghi số/tên/ngày sinh/phong bì/key hoặc URL ký. */
    @Test void privateHttpResponseDoesNotLeakIntoLogs() throws Exception {
        String code = prepare("IDENTITY_CARD", "PENDING");
        account(verifier, true);
        var stored = reads.findForReviewByCode(code).orElseThrow();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>(); logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs);
        binds.setLevel(Level.TRACE);
        try {
            success(open(verifier, code, null));
            var secrets = List.of(fakeNumber(ownerCode), "LOCAL FAKE DOCUMENT", "1990-01-01", stored.number().nonceBase64(),
                    stored.number().ciphertextBase64(), stored.number().lookupHash(), stored.frontImage().sealedObjectKey(),
                    stored.backImage().sealedObjectKey(), "https://local.invalid/", "signature=fake");
            for (var event : List.copyOf(logs.list)) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : secrets) assertFalse(text.contains(secret), "Sensitive document data leaked to logs.");
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertTrue(p6.stream().anyMatch(e -> e.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Tạo hồ sơ mã hoá và hai ảnh ở container tạm; không ghi dữ liệu local thật. */
    private String prepare(String type, String status) {
        var parameters = submission(ownerId, type, status); long id = insert("submission", parameters);
        for (DocumentSide side : DocumentSide.values()) {
            long upload = insert("upload", upload(ownerId, type, side.name()));
            insert("image", image(id, upload, ownerId, type, side.name()));
        }
        return (String) parameters.getValue("code");
    }

    /** Lấy mã TK từ JWT qua API thật, không gán sẵn CallerRef trong test. */
    private String account(String bearer, boolean admin) throws Exception {
        var response = send(bearer, "/api/v1/" + (admin ? "admin" : "client") + "/accounts/me", "GET", null);
        assertEquals(200, response.statusCode(), () -> "Account endpoint content type: "
                + response.headers().firstValue("Content-Type").orElse("none") + "; port=" + port);
        return json.readTree(response.body()).path("data").path("code").asString();
    }

    /** Mỗi lần gọi đúng một POST, không tự retry khi server lỗi. */
    private HttpResponse<String> open(String bearer, String code, String body) throws Exception {
        return send(bearer, path(code), "POST", body);
    }

    /** Đường dẫn theo mã nghiệp vụ, không đưa khoá chính số vào URL. */
    private static String path(String code) { return "/api/v1/admin/documents/" + code + "/view-sessions"; }

    /** Gửi socket thật; header giả không được thay người xem do JWT quyết định. */
    private HttpResponse<String> send(String bearer, String path, String method, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .header("X-Account-Code", "TK-FAKE01").header("X-Role", "ADMIN");
        if (requestId != null) builder.header("X-Request-Id", requestId);
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Đọc connection ở thread test, độc lập với transaction của thread HTTP. */
    private List<Map<String, Object>> events() {
        return jdbc.queryForList(sqlLoader.load("sql/audit/read_events.sql"), Map.of("requestId", requestId));
    }

    /** Audit phải ghi đúng actor JWT, chủ từ CSDL, GT và requestId, không lấy header giả. */
    private void assertAudit(String code, String actor, int count) {
        var rows = events(); assertEquals(count, rows.size(), "Audit must commit before signing.");
        for (var event : rows) {
            assertEquals(actor, event.get("actor_account_code")); assertEquals(ownerCode, event.get("subject_account_code"));
            assertEquals(code, event.get("resource_code")); assertEquals(requestId, event.get("request_id"));
            assertEquals("DOCUMENT_VIEW_OPENED", event.get("action")); assertNotNull(event.get("occurred_at"));
        }
    }

    /** Kiểm 201 và envelope nhưng không in payload nhạy cảm vào thông báo assert. */
    private JsonNode success(HttpResponse<String> response) {
        assertEquals(201, response.statusCode()); privateHeaders(response);
        if (requestId != null && requestId.matches("[A-Za-z0-9-]{1,64}")) {
            assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
        }
        var body = json.readTree(response.body()); assertTrue(body.path("success").asBoolean()); assertTrue(body.path("error").isNull());
        return body.path("data");
    }

    /** Lỗi phải đúng HTTP lẫn mã; không xem 403 và 500 là cùng một kết quả. */
    private void failure(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode()); privateHeaders(response);
        var body = json.readTree(response.body()); assertFalse(body.path("success").asBoolean()); assertTrue(body.path("data").isNull());
        assertEquals(code, body.path("error").path("code").asString());
    }

    /** Response 500 không lộ dữ liệu giấy tờ, URL, SQL, lớp hay stack; log lỗi đã được xử lý riêng. */
    private void safeFailure(HttpResponse<String> response) {
        failure(response, 500, "INTERNAL_ERROR");
        for (String value : new String[]{"LOCAL-FAKE-NUMBER-", "LOCAL FAKE DOCUMENT", "signature", "sealed/", "SELECT", "Exception", "com.carrental", "Forced"}) {
            assertFalse(response.body().contains(value));
        }
    }

    /** Dữ liệu riêng tư và lỗi không cache; ID truy vết luôn nằm trong tập ký tự an toàn. */
    private static void privateHeaders(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(response.headers().firstValue("X-Request-Id").orElse("").matches("[A-Za-z0-9-]{1,64}"));
    }

    /** Kiểm tên trường thay vì tìm chuỗi ID số có thể vô tình trùng nội dung URL hoặc ngày. */
    private static Set<String> keys(JsonNode node) {
        var result = new HashSet<String>(); node.properties().forEach(entry -> result.add(entry.getKey())); return result;
    }
}
