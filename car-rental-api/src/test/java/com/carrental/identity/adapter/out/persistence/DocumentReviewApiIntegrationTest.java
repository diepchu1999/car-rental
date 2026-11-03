package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.identity.adapter.in.rest.admin.request.RejectDocumentRequest;
import com.carrental.identity.adapter.in.rest.admin.response.DocumentReviewResponse;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: JWT, HTTP, service và PostgreSQL thật; chỉ storage ngoài luồng được giả tại biên. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentReviewApiIntegrationTest extends DocumentDatabaseTestSupport {
    private static final String PATH = "/api/v1/admin/documents/";
    private static final String REJECTION = "{\"rejectionCode\":\"ILLEGIBLE\",\"rejectionNote\":\"Local fake note\"}";
    @LocalServerPort int port;
    @Autowired TestJwtIssuer tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoSpyBean DocumentNumberCryptoPort numbers;
    @MockitoSpyBean WriteAuditEventPort audits;
    @MockitoBean DocumentObjectPort objects;
    private String customer;
    private String verifier;
    private String admin;
    private String owner;
    private String reviewer;
    private long ownerId;
    private String requestId;

    /** Đồng bộ tài khoản bằng HTTP thật trước fixture, không giả CallerDirectory hoặc ghi vai trong CSDL. */
    @BeforeEach void setUp() throws Exception {
        requestId = "review-" + UUID.randomUUID();
        customer = tokens.newCustomerToken(clock); verifier = tokens.documentVerifierToken(clock); admin = tokens.adminToken(clock);
        owner = account(customer, "client"); reviewer = account(verifier, "admin"); account(admin, "admin");
        ownerId = jdbc.queryForObject(documentSql("find_account_id_by_code"), Map.of("code", owner), Long.class);
        clearInvocations(reads, numbers, audits, objects);
    }

    /** Quyết định chỉ đọc metadata, không giải mã số, mở ảnh hoặc sinh audit lượt xem. */
    @AfterEach void doesNotOpenImages() {
        verify(numbers, never()).reveal(any(), any()); verifyNoInteractions(audits, objects);
    }

    /** Hai vai cấp công ty duyệt được cả hai loại; người/thời điểm từ server, chủ đọc được kết quả đã commit. */
    @ParameterizedTest @CsvSource({"ADMIN,IDENTITY_CARD", "ADMIN,DRIVER_LICENSE", "DOCUMENT_VERIFIER,IDENTITY_CARD", "DOCUMENT_VERIFIER,DRIVER_LICENSE"})
    void verifiesAndReturnsCommittedMetadata(String role, String type) throws Exception {
        var fixture = pending(type); String bearer = role.equals("ADMIN") ? admin : verifier;
        String actor = account(bearer, "admin"); Instant before = clock.instant();
        var data = success(post(bearer, fixture.code(), "verify", "{}"), fixture, "VERIFIED");
        Instant reviewedAt = Instant.parse(data.path("reviewedAt").asString());
        assertFalse(reviewedAt.isBefore(before)); assertFalse(reviewedAt.isAfter(clock.instant()));
        var stored = row(fixture.code()); assertEquals(actor, stored.get("reviewer_code"));
        assertEquals(fixture.code(), stored.get("active_code")); assertEquals("VERIFIED", row(fixture.oldCode()).get("status"));
        assertTrue(data.path("rejectionCode").isNull()); assertTrue(data.path("rejectionNote").isNull());
        assertEquals(data, json.readTree(get(customer, "/api/v1/client/documents/" + fixture.code()).body()).path("data"));
    }

    /** Endpoint xác minh không bắt buộc gửi JSON rỗng. */
    @Test void permitsVerificationWithoutBody() throws Exception {
        var fixture = pending("IDENTITY_CARD"); success(post(verifier, fixture.code(), "verify", null), fixture, "VERIFIED");
    }

    /** Sáu lý do BR-130 đi hết HTTP; từ chối vẫn giữ bản VERIFIED cũ làm active. */
    @ParameterizedTest @ValueSource(strings = {"ILLEGIBLE", "MISSING_SIDE", "DATA_MISMATCH", "EXPIRED", "HOLDER_MISMATCH", "SUSPECTED_FORGERY"})
    void rejectsWithEachSupportedReason(String reason) throws Exception {
        var fixture = pending("DRIVER_LICENSE");
        var data = success(post(verifier, fixture.code(), "reject", json.writeValueAsString(Map.of(
                "rejectionCode", reason, "rejectionNote", "Local fake note\nsecond line"))), fixture, "REJECTED");
        assertEquals(reason, data.path("rejectionCode").asString());
        assertEquals("Local fake note\nsecond line", data.path("rejectionNote").asString());
        assertEquals(fixture.oldCode(), row(fixture.code()).get("active_code"));
        assertEquals(reviewer, row(fixture.code()).get("reviewer_code"));
        assertEquals(data, json.readTree(get(customer, "/api/v1/client/documents/" + fixture.code()).body()).path("data"));
    }

    /** ADMIN cũng từ chối được; ghi chú là tùy chọn và trả null thay vì tự sinh nội dung. */
    @Test void adminCanRejectWithoutNote() throws Exception {
        var fixture = pending("IDENTITY_CARD");
        var data = success(post(admin, fixture.code(), "reject", "{\"rejectionCode\":\"ILLEGIBLE\"}"), fixture, "REJECTED");
        assertTrue(data.path("rejectionNote").isNull());
        assertEquals(account(admin, "admin"), row(fixture.code()).get("reviewer_code"));
    }

    /** Mọi trường giả trong verify bị chặn trước write; dùng làm phép thử đảo JsonAnySetter. */
    @ParameterizedTest @ValueSource(strings = {"reviewerAccountCode", "reviewedAt", "accountCode", "status"})
    void rejectsVerifyBodyFields(String field) throws Exception {
        var fixture = pending("IDENTITY_CARD");
        failure(post(verifier, fixture.code(), "verify", json.writeValueAsString(Map.of(field, "FAKE"))), 400, "INVALID_REQUEST");
        assertPending(fixture);
    }

    /** Lý do/ghi chú sai, JSON hỏng hoặc trường ngoài hợp đồng không được biến thành quyết định hợp lệ. */
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{", "{\"rejectionCode\":null}",
            "{\"rejectionCode\":\"UNKNOWN\"}", "{\"rejectionCode\":\"illegible\"}",
            "{\"rejectionCode\":\"ILLEGIBLE\",\"rejectionNote\":\" \"}",
            "{\"rejectionCode\":\"ILLEGIBLE\",\"rejectionNote\":\"\\u0000\"}",
            "{\"rejectionCode\":\"ILLEGIBLE\",\"reviewerAccountCode\":\"TK-FAKE01\"}"})
    void rejectsInvalidRejectionBodies(String body) throws Exception {
        var fixture = pending("IDENTITY_CARD");
        failure(post(verifier, fixture.code(), "reject", body), 400, "INVALID_REQUEST"); assertPending(fixture);
    }

    /** Thiếu JWT, khách và STAFF đều không thay đổi dữ liệu qua cả hai endpoint, kể cả có header giả vai. */
    @ParameterizedTest @ValueSource(strings = {"NONE", "CUSTOMER", "BRANCH_STAFF"})
    void deniedCallersCannotWrite(String role) throws Exception {
        var fixture = pending("IDENTITY_CARD");
        String bearer = role.equals("NONE") ? null : role.equals("CUSTOMER") ? customer : tokens.branchStaffToken(clock);
        int status = role.equals("NONE") ? 401 : 403; String error = status == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED";
        failure(post(bearer, fixture.code(), "verify", "{}"), status, error);
        failure(post(bearer, fixture.code(), "reject", REJECTION), status, error); assertPending(fixture);
    }

    /** Phân biệt cú pháp mã sai 400 với mã đúng nhưng không có 404 trên cả hai thao tác. */
    @ParameterizedTest @ValueSource(strings = {"verify", "reject"})
    void distinguishesInvalidAndMissingCode(String action) throws Exception {
        failure(post(verifier, "invalid", action, action.equals("verify") ? "{}" : REJECTION), 400, "INVALID_REQUEST");
        failure(post(verifier, "GT-NONE00", action, action.equals("verify") ? "{}" : REJECTION), 404, "DOCUMENT_NOT_FOUND");
    }

    /** Quyết định đã chốt không bị ghi đè khi gọi lại hoặc đổi thao tác. */
    @ParameterizedTest @ValueSource(strings = {"verify", "reject"})
    void completedDecisionCannotBeOverwritten(String action) throws Exception {
        var fixture = pending("IDENTITY_CARD");
        success(post(verifier, fixture.code(), action, action.equals("verify") ? "{}" : REJECTION),
                fixture, action.equals("verify") ? "VERIFIED" : "REJECTED");
        var stored = row(fixture.code());
        failure(post(admin, fixture.code(), "verify", "{}"), 409, "DOCUMENT_REVIEW_CONFLICT");
        failure(post(admin, fixture.code(), "reject", REJECTION), 409, "DOCUMENT_REVIEW_CONFLICT");
        assertEquals(stored, row(fixture.code()));
    }

    /** CCCD đã có chủ trả đúng 409 qua HTTP; bản pending bên thua và active cũ không đổi. */
    @Test void rejectsCardAlreadyRegisteredToAnotherAccount() throws Exception {
        String number = "LOCAL-FAKE-" + UUID.randomUUID(); var first = pending("IDENTITY_CARD", number);
        success(post(verifier, first.code(), "verify", "{}"), first, "VERIFIED");
        customer = tokens.newCustomerToken(clock); owner = account(customer, "client");
        ownerId = jdbc.queryForObject(documentSql("find_account_id_by_code"), Map.of("code", owner), Long.class);
        var second = pending("IDENTITY_CARD", number);
        failure(post(verifier, second.code(), "verify", "{}"), 409, "IDENTITY_CARD_ALREADY_REGISTERED");
        assertPending(second);
    }

    /** Reload hỏng sau write trả lỗi kín và rollback; không để 500 nhưng dữ liệu đã được xác minh/từ chối. */
    @ParameterizedTest @ValueSource(strings = {"verify", "reject"})
    void internalReloadFailureRollsBackAndStaysPrivate(String action) throws Exception {
        var fixture = pending("IDENTITY_CARD");
        doReturn(Optional.empty()).when(reads).findStatusForReviewByCode(fixture.code());
        var response = post(verifier, fixture.code(), action, action.equals("verify") ? "{}" : REJECTION);
        failure(response, 500, "INTERNAL_ERROR"); assertPending(fixture);
        for (String detail : List.of("Exception", "com.carrental", "SELECT", "reloaded", "java:")) assertFalse(response.body().contains(detail));
        assertTrue(SensitiveSql.execute(() -> jdbc.queryForList(documentSql("read_registration_for_test"),
                Map.of("code", fixture.code()), String.class)).isEmpty());
    }

    /** Hai HTTP thật tranh quyết định cùng bản: một 200, một 409, reviewer/trạng thái cuối thuộc bên thắng. */
    @Test void concurrentDecisionsHaveExactlyOneWinner() throws Exception {
        var fixture = pending("IDENTITY_CARD"); String adminCode = account(admin, "admin");
        try (var http = HttpClient.newHttpClient()) {
            var first = http.sendAsync(request(verifier, PATH + fixture.code() + "/verify", "{}"), HttpResponse.BodyHandlers.ofString());
            var second = http.sendAsync(request(admin, PATH + fixture.code() + "/reject", REJECTION), HttpResponse.BodyHandlers.ofString());
            var verified = first.get(30, TimeUnit.SECONDS); var rejected = second.get(30, TimeUnit.SECONDS);
            var stored = row(fixture.code());
            if (verified.statusCode() == 200) {
                success(verified, fixture, "VERIFIED"); failure(rejected, 409, "DOCUMENT_REVIEW_CONFLICT");
                assertEquals(reviewer, stored.get("reviewer_code")); assertEquals(fixture.code(), stored.get("active_code"));
            } else {
                failure(verified, 409, "DOCUMENT_REVIEW_CONFLICT"); success(rejected, fixture, "REJECTED");
                assertEquals(adminCode, stored.get("reviewer_code")); assertEquals(fixture.oldCode(), stored.get("active_code"));
            }
            assertEquals(verified.statusCode() == 200 ? "VERIFIED" : "REJECTED", stored.get("status"));
        }
    }

    /** TRACE và sql-log không in số/phong bì/ghi chú qua đường HTTP thật; DTO toString cũng phải che. */
    @Test void reviewHttpKeepsSensitiveMaterialOutOfLogs() throws Exception {
        var fixture = pending("IDENTITY_CARD"); String note = "LOCAL-FAKE-NOTE-" + UUID.randomUUID();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel(); var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            var data = success(post(verifier, fixture.code(), "reject", json.writeValueAsString(Map.of(
                    "rejectionCode", "ILLEGIBLE", "rejectionNote", note))), fixture, "REJECTED");
            assertEquals("DocumentReviewResponse[redacted]", json.treeToValue(data, DocumentReviewResponse.class).toString());
            assertEquals("RejectDocumentRequest[redacted]", new RejectDocumentRequest("ILLEGIBLE", note).toString());
            for (var event : List.copyOf(logs.list)) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertFalse(text.contains(note), "Rejection note leaked into logs.");
                for (String secret : fixture.secrets()) assertFalse(text.contains(secret), "Document material leaked into logs.");
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log")))
                assertTrue(p6.stream().anyMatch(e -> e.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Tạo số mới, không dùng dữ liệu local hoặc số giấy tờ thật. */
    private Fixture pending(String type) { return pending(type, "LOCAL-FAKE-" + UUID.randomUUID()); }

    /** Commit bản VERIFIED cũ và pending mới; không cần ảnh để kiểm đường quyết định không mở ảnh. */
    private Fixture pending(String type, String number) {
        var old = submission(ownerId, type, "VERIFIED"); long oldId = insert("submission", old);
        var current = submission(ownerId, type, "PENDING", number); long currentId = insert("submission", current);
        insert("head", head(ownerId, type, currentId, oldId));
        return new Fixture((String) current.getValue("code"), (String) old.getValue("code"), type,
                List.of(number, (String) current.getValue("nonce"), (String) current.getValue("ciphertext"), (String) current.getValue("hash")));
    }

    /** Fixture chỉ ở test; che cả record để assertion phụ không vô tình in phong bì. */
    private record Fixture(String code, String oldCode, String type, List<String> secrets) {
        /** Không in số hoặc phong bì khi fixture tham gia thông báo lỗi. */
        @Override public String toString() { return "Fixture[redacted]"; }
    }

    /** Đọc độc lập sau commit/rollback, không dùng response làm đáp án CSDL. */
    private Map<String, Object> row(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForMap(documentSql("read_review_for_test"), Map.of("code", code)));
    }

    /** Thất bại không đổi bản chờ, người duyệt hoặc active cũ. */
    private void assertPending(Fixture fixture) {
        var stored = row(fixture.code()); assertEquals("PENDING", stored.get("status"));
        assertNull(stored.get("reviewer_code")); assertNull(stored.get("reviewed_at")); assertNull(stored.get("rejection_note"));
        assertEquals(fixture.oldCode(), stored.get("active_code")); assertEquals(fixture.code(), stored.get("latest_code"));
    }

    /** Đồng bộ tài khoản bằng JWT rồi chỉ lấy mã nghiệp vụ, không in response chứa điện thoại. */
    private String account(String bearer, String audience) throws Exception {
        var response = get(bearer, "/api/v1/" + audience + "/accounts/me"); assertEquals(200, response.statusCode());
        return json.readTree(response.body()).path("data").path("code").asString();
    }

    /** HTTP POST thật, không retry quyết định và không in token/body. */
    private HttpResponse<String> post(String bearer, String code, String action, String body) throws Exception {
        try (var http = HttpClient.newHttpClient()) { return http.send(request(bearer, PATH + code + "/" + action, body), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Tạo request với requestId cố định theo test và header giả vai để chứng minh server không tin nó. */
    private HttpRequest request(String bearer, String path, String body) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .header("X-Request-Id", requestId).header("X-Role", "ADMIN");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /** GET metadata chủ qua cùng server bảo mật, không gọi trực tiếp controller. */
    private HttpResponse<String> get(String bearer, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearer).header("X-Request-Id", requestId);
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Kiểm đủ hợp đồng chín trường, envelope, cache và requestId; không lộ dữ liệu ngoài metadata. */
    private JsonNode success(HttpResponse<String> response, Fixture fixture, String status) {
        assertEquals(200, response.statusCode()); headers(response); var body = json.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), keys(body)); assertTrue(body.path("success").asBoolean()); assertTrue(body.path("error").isNull());
        var data = body.path("data");
        assertEquals(Set.of("code", "documentType", "status", "expiresOn", "issuedOn", "submittedAt", "reviewedAt", "rejectionCode", "rejectionNote"), keys(data));
        if (fixture.type().equals("DRIVER_LICENSE")) assertEquals("2025-01-01", data.path("issuedOn").asString());
        else assertTrue(data.path("issuedOn").isNull());
        assertEquals(fixture.code(), data.path("code").asString()); assertEquals(fixture.type(), data.path("documentType").asString());
        assertEquals(status, data.path("status").asString()); assertEquals("2030-01-01", data.path("expiresOn").asString());
        assertEquals(Instant.parse(AT), Instant.parse(data.path("submittedAt").asString())); assertFalse(data.path("reviewedAt").isNull());
        return data;
    }

    /** Chỉ mã HTTP và mã lỗi chính xác mới đạt, không in response thô khi thất bại. */
    private void failure(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode()); headers(response); var body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean()); assertTrue(body.path("data").isNull());
        assertEquals(code, body.path("error").path("code").asString());
    }

    /** Kết quả và lỗi đều không cache và trả ID truy vết từ request. */
    private void headers(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    /** So tên trường đầy đủ để bắt cả thuộc tính riêng tư vô tình được thêm. */
    private static Set<String> keys(JsonNode node) {
        var names = new HashSet<String>(); node.properties().forEach(e -> names.add(e.getKey())); return names;
    }
}
