package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.adapter.in.rest.client.response.DocumentHeadResponse;
import com.carrental.identity.adapter.in.rest.client.response.DocumentStatusResponse;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP/JWT/use case/SQL/PostgreSQL thật; cùng package fixture để chuẩn bị trạng thái chưa có API duyệt. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentStatusApiIntegrationTest extends DocumentDatabaseTestSupport {
    private static final String PATH = "/api/v1/client/documents";
    @LocalServerPort private int port;
    @Autowired TestJwtIssuer tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoSpyBean WriteDocumentSubmissionPort writes;
    @MockitoBean DocumentObjectPort objects;
    private String token;
    private String ownerCode;
    private long ownerId;

    /** Khách mới mỗi test, đồng bộ account bằng JWT qua HTTP; fixture đã commit để thread HTTP đọc được. */
    @BeforeEach void prepareCustomer() throws Exception {
        token = tokens.newCustomerToken(clock);
        var response = get(token, "/api/v1/client/accounts/me");
        assertEquals(200, response.statusCode());
        ownerCode = json.readTree(response.body()).path("data").path("code").asString();
        Long id = jdbc.queryForObject(documentSql("find_account_id_by_code"), Map.of("code", ownerCode), Long.class);
        assertNotNull(id); ownerId = id;
    }

    /** GET không chốt/đọc/ký ảnh hoặc gọi write port, kể cả khi gặp lỗi. */
    @AfterEach void doesNotWriteDocumentsOrAccessObjects() { verifyNoInteractions(objects, writes); }

    /** Mọi trạng thái trả đủ tám trường metadata; reviewed/rejection null đúng, không có ID/số/key ảnh. */
    @ParameterizedTest @EnumSource(DocumentSubmissionStatus.class)
    void readsOwnVersionWithExactResponseShape(DocumentSubmissionStatus status) throws Exception {
        for (String type : new String[]{"IDENTITY_CARD", "DRIVER_LICENSE"}) {
            var fixture = submission(ownerId, type, status.name());
            insert("submission", fixture);
            String code = (String) fixture.getValue("code");
            var response = get(token, PATH + "/" + code);
            var data = success(response);
            assertMetadata(data);
            assertEquals(code, data.path("code").asString());
            assertEquals(type, data.path("documentType").asString());
            assertEquals(status.name(), data.path("status").asString());
            assertEquals("2030-01-01", data.path("expiresOn").asString());
            assertEquals(AT, data.path("submittedAt").asString());
            boolean reviewed = status == DocumentSubmissionStatus.VERIFIED || status == DocumentSubmissionStatus.REJECTED;
            if (reviewed) assertEquals(AT, data.path("reviewedAt").asString());
            else assertTrue(data.path("reviewedAt").isNull());
            if (status == DocumentSubmissionStatus.REJECTED) assertEquals("ILLEGIBLE", data.path("rejectionCode").asString());
            else assertTrue(data.path("rejectionCode").isNull());
            assertTrue(data.path("rejectionNote").isNull());
            assertFalse(response.body().contains(fakeNumber(ownerCode)));
            assertFalse(response.body().contains("LOCAL FAKE DOCUMENT"));
        }
    }

    /** Khách nhận đúng sáu lý do và ghi chú của mình, cả khi đọc một bản và đọc head. */
    @ParameterizedTest @ValueSource(strings = {"ILLEGIBLE", "MISSING_SIDE", "DATA_MISMATCH", "EXPIRED", "HOLDER_MISMATCH", "SUSPECTED_FORGERY"})
    void readsOwnRejectionReason(String reason) throws Exception {
        var rejected = submission(ownerId, "IDENTITY_CARD", "REJECTED")
                .addValue("rejectionCode", reason).addValue("rejectionNote", "LOCAL PRIVATE REVIEW NOTE");
        long id = insert("submission", rejected);
        insert("head", head(ownerId, "IDENTITY_CARD", id, null));
        var detail = success(get(token, PATH + "/" + rejected.getValue("code")));
        assertEquals(reason, detail.path("rejectionCode").asString());
        assertEquals("LOCAL PRIVATE REVIEW NOTE", detail.path("rejectionNote").asString());
        assertEquals(detail, success(get(token, PATH)).get(0).path("latestSubmission"));
    }

    /** Một GT của A không thể đọc bằng JWT B, kể cả B khai accountCode/role của A ở query/header. */
    @Test void hidesForeignDocumentLikeMissingDocument() throws Exception {
        var fixture = submission(ownerId, "IDENTITY_CARD", "PENDING");
        insert("submission", fixture);
        String code = (String) fixture.getValue("code");
        success(get(token, PATH + "/" + code));
        String stranger = tokens.newCustomerToken(clock);
        var foreign = get(stranger, PATH + "/" + code + "?accountCode=" + ownerCode + "&role=ADMIN");
        var missing = get(stranger, PATH + "/GT-ZZZZZZ");
        failure(foreign, 404, "DOCUMENT_NOT_FOUND");
        failure(missing, 404, "DOCUMENT_NOT_FOUND");
        assertEquals(json.readTree(missing.body()), json.readTree(foreign.body()));
    }

    /** Không có hồ sơ trả [], tham số chủ giả không đổi người gọi; head của A không xuất hiện cho B. */
    @Test void listsOnlyCallerHeadsAndKeepsEmptyList() throws Exception {
        assertEquals(0, success(get(token, PATH)).size());
        var pending = submission(ownerId, "IDENTITY_CARD", "PENDING");
        insert("head", head(ownerId, "IDENTITY_CARD", insert("submission", pending), null));
        var own = success(get(token, PATH));
        assertEquals(1, own.size());
        assertHead(own.get(0));
        assertEquals(pending.getValue("code"), own.get(0).path("latestSubmission").path("code").asString());
        assertTrue(own.get(0).path("activeVerifiedSubmission").isNull());
        String stranger = tokens.newCustomerToken(clock);
        var other = success(get(stranger, PATH + "?accountCode=" + ownerCode));
        assertTrue(other.isArray()); assertEquals(0, other.size());
    }

    /** Latest pending/rejected và active verified hết hạn vẫn được biểu diễn riêng, không kết luận đủ điều kiện thuê. */
    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void separatesPendingOrRejectedLatestFromVerifiedActive(String status) throws Exception {
        for (String type : new String[]{"IDENTITY_CARD", "DRIVER_LICENSE"}) {
            var active = submission(ownerId, type, "VERIFIED").addValue("expiresOn", "2000-01-01");
            long activeId = insert("submission", active);
            var latest = submission(ownerId, type, status);
            insert("head", head(ownerId, type, insert("submission", latest), activeId));
        }
        var result = success(get(token, PATH));
        assertEquals(2, result.size());
        assertEquals("DRIVER_LICENSE", result.get(0).path("documentType").asString());
        assertEquals("IDENTITY_CARD", result.get(1).path("documentType").asString());
        for (var head : result) {
            assertHead(head);
            assertEquals(status, head.path("latestSubmission").path("status").asString());
            assertEquals("VERIFIED", head.path("activeVerifiedSubmission").path("status").asString());
            assertEquals("2000-01-01", head.path("activeVerifiedSubmission").path("expiresOn").asString());
            assertNotEquals(head.path("latestSubmission").path("code"), head.path("activeVerifiedSubmission").path("code"));
        }
    }

    /** Cả hai GET thiếu token trả 401, nhân viên ADMIN không có CUSTOMER trả 403 trước đọc hồ sơ. */
    @Test void rejectsMissingAndStaffTokens() throws Exception {
        clearInvocations(reads);
        for (String path : new String[]{PATH, PATH + "/GT-LOCAL1"}) {
            failure(get(null, path), 401, "UNAUTHENTICATED");
            failure(get(tokens.adminToken(clock), path), 403, "ACCESS_DENIED");
        }
        verifyNoInteractions(reads);
    }

    /** ID số, sai prefix, chữ thường và mã dài/ngắn đều 400, không biến thành tra cứu tuỳ ý. */
    @ParameterizedTest @ValueSource(strings = {"123", "AN-LOCAL1", "gt-LOCAL1", "GT-ABC", "GT-LOCAL11", "GT-LOCAL!"})
    void rejectsMalformedCode(String code) throws Exception {
        clearInvocations(reads);
        failure(get(token, PATH + "/" + code), 400, "INVALID_REQUEST");
        verifyNoInteractions(reads);
    }

    /** Transaction của use case thật là readOnly; lỗi bên trong trả 500 kín ở cả hai endpoint. */
    @Test void usesReadOnlyTransactionsAndHidesInternalFailures() throws Exception {
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(reads).listHeadsByOwner(anyString());
        success(get(token, PATH));
        doThrow(new IllegalStateException("PRIVATE SELECT number_ciphertext FROM identity.document_submission"))
                .when(reads).listHeadsByOwner(anyString());
        doThrow(new IllegalStateException("PRIVATE sealed/key INTERNAL STACK"))
                .when(reads).findStatusByCodeAndOwner(anyString(), anyString());
        for (String path : new String[]{PATH, PATH + "/GT-LOCAL1"}) {
            var response = get(token, path);
            failure(response, 500, "INTERNAL_ERROR");
            for (String hidden : new String[]{"PRIVATE", "SELECT", "sealed/", "Exception", "number_ciphertext", "com.carrental"}) {
                assertFalse(response.body().contains(hidden));
            }
        }
    }

    /** DTO response không được tự in ghi chú riêng tư khi logger nhận cả object. */
    @Test void redactsResponseStrings() {
        var detail = new DocumentStatusResponse("GT-LOCAL1", "IDENTITY_CARD", "REJECTED", null, null, null,
                "ILLEGIBLE", "LOCAL PRIVATE REVIEW NOTE");
        assertEquals("DocumentStatusResponse[redacted]", detail.toString());
        assertEquals("DocumentHeadResponse[redacted]", new DocumentHeadResponse("IDENTITY_CARD", detail, null).toString());
    }

    /** Gửi qua socket thật, kèm header chủ/vai giả để chứng minh backend không lấy danh tính từ đó. */
    private HttpResponse<String> get(String bearer, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .header("X-Account-Code", "TK-FAKE01").header("X-Role", "ADMIN").GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        try (var http = HttpClient.newHttpClient()) { return http.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Thành công phải đúng envelope, requestId và no-store; không in body riêng tư khi assertion lỗi. */
    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode()); privateHeaders(response);
        var body = json.readTree(response.body());
        assertTrue(body.path("success").asBoolean()); assertTrue(body.path("error").isNull());
        return body.path("data");
    }

    /** Chỉ đạt khi đúng HTTP lẫn error.code, không coi mọi lỗi xác thực là tương đương. */
    private void failure(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode()); privateHeaders(response);
        var body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean()); assertTrue(body.path("data").isNull());
        assertEquals(code, body.path("error").path("code").asString());
    }

    /** Cả success và failure không được cache; correlation dùng header an toàn đã có. */
    private static void privateHeaders(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(response.headers().firstValue("X-Request-Id").orElse("").matches("[A-Za-z0-9-]{1,64}"));
    }

    /** Danh sách chỉ trả loại và hai con trỏ dạng metadata, không có trường nội bộ phát sinh. */
    private static void assertHead(JsonNode data) {
        assertEquals(Set.of("documentType", "latestSubmission", "activeVerifiedSubmission"), keys(data));
        assertMetadata(data.path("latestSubmission"));
        if (!data.path("activeVerifiedSubmission").isNull()) assertMetadata(data.path("activeVerifiedSubmission"));
    }

    /** Khoá chính xác bộ tám trường, nên thêm ID/key/số giấy tờ vào response làm test đỏ. */
    private static void assertMetadata(JsonNode data) {
        assertEquals(Set.of("code", "documentType", "status", "expiresOn", "submittedAt", "reviewedAt",
                "rejectionCode", "rejectionNote"), keys(data));
    }

    /** Đọc tên thuộc tính JSON mà không ghi nội dung giá trị ra log. */
    private static Set<String> keys(JsonNode node) {
        var keys = new HashSet<String>(); node.properties().forEach(entry -> keys.add(entry.getKey())); return keys;
    }
}
