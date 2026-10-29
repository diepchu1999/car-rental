package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.identity.adapter.in.rest.admin.response.PendingDocumentResponse;
import com.carrental.identity.adapter.in.rest.admin.response.PendingDocumentsResponse;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.storage.application.port.out.DocumentObjectPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** BR-130: HTTP/JWT/use case/SQL/PostgreSQL thật; không giả caller hoặc kết quả đọc hàng chờ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentQueueApiIntegrationTest extends DocumentDatabaseTestSupport {
    private static final String PATH = "/api/v1/admin/documents";
    @LocalServerPort int port;
    @Autowired WebServerApplicationContext serverContext;
    @Autowired TestJwtIssuer tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @MockitoSpyBean ReadDocumentSubmissionPort reads;
    @MockitoSpyBean DocumentNumberCryptoPort numbers;
    @MockitoSpyBean WriteAuditEventPort audits;
    @MockitoBean DocumentObjectPort objects;
    private final List<PendingDocumentResponse> expected = new ArrayList<>();
    private final List<String> secrets = new ArrayList<>();
    private String verifier;
    private String requestId;

    /** Bộ dữ liệu cố định, commit trước HTTP: 25 bản chờ hai loại, thêm sáu bản không chờ; không có ảnh. */
    @BeforeAll void seedQueue() {
        for (int i = 0; i < 31; i++) {
            var account = accountParameters();
            // Tránh trùng số fixture của JWT nhân viên, không sử dụng tài khoản local.
            account.addValue("phone", "+8493" + ((String) account.getValue("code")).substring(3) + "0");
            long owner = insertAccount(account);
            String type = i % 2 == 0 ? "IDENTITY_CARD" : "DRIVER_LICENSE";
            String status = i < 25 ? "PENDING" : new String[]{"VERIFIED", "REJECTED", "SUPERSEDED"}[(i - 25) / 2];
            var parameters = submission(owner, type, status); insert("submission", parameters);
            if (i < 25) expected.add(new PendingDocumentResponse((String) parameters.getValue("code"),
                    accountCode(owner), type, Instant.parse(AT)));
            for (String field : new String[]{"nonce", "ciphertext", "hash"}) secrets.add((String) parameters.getValue(field));
        }
        secrets.addAll(List.of(FAKE_NUMBER, "LOCAL FAKE DOCUMENT", "1990-01-01", "signature=", "sealed/"));
    }

    /** Mỗi test lấy JWT mới để không phụ thuộc hạn token hoặc thứ tự test; kiểm đúng server context. */
    @BeforeEach void setUp() {
        assertEquals(serverContext.getWebServer().getPort(), port);
        verifier = tokens.documentVerifierToken(clock); requestId = "queue-" + UUID.randomUUID();
        clearInvocations(reads, numbers, audits, objects);
    }

    /** GET hàng chờ không mở nội dung, cấp URL hoặc tạo audit lượt xem. */
    @AfterEach void doesNotOpenDocuments() {
        verify(numbers, never()).reveal(any(), any()); verifyNoInteractions(audits, objects);
    }

    /** ADMIN và verifier đều thấy toàn bộ chủ/loại, chỉ PENDING, đúng bốn trường metadata và không cache. */
    @ParameterizedTest @ValueSource(strings = {"ADMIN", "DOCUMENT_VERIFIER"})
    void authorizedRolesReceiveExactQueue(String role) throws Exception {
        String bearer = role.equals("ADMIN") ? tokens.adminToken(clock) : verifier;
        var data = success(get(bearer, "?limit=100"));
        assertEquals(expected, items(data)); assertFalse(data.path("hasMore").asBoolean());
        verify(reads).searchPending(101, 0);
    }

    /** Không có tham số dùng 20/0; chuỗi rỗng cũng được Spring bind thành null và dùng mặc định. */
    @Test void defaultsToTwentyItems() throws Exception {
        for (String query : new String[]{"", "?limit=&offset="}) {
            var data = success(get(verifier, query));
            assertEquals(expected.subList(0, 20), items(data)); assertTrue(data.path("hasMore").asBoolean());
        }
        verify(reads, times(2)).searchPending(21, 0);
    }

    /** Hai trang liên tiếp không trùng khi dữ liệu không đổi; trang cuối và offset rất lớn trả đúng hasMore. */
    @Test void paginatesAndReturnsEmptyPage() throws Exception {
        var first = success(get(verifier, "?limit=20&offset=0"));
        var last = success(get(verifier, "?limit=20&offset=20"));
        assertEquals(expected.subList(0, 20), items(first)); assertTrue(first.path("hasMore").asBoolean());
        assertEquals(expected.subList(20, 25), items(last)); assertFalse(last.path("hasMore").asBoolean());
        var empty = success(get(verifier, "?limit=1&offset=9223372036854775807"));
        assertTrue(items(empty).isEmpty()); assertFalse(empty.path("hasMore").asBoolean());
    }

    /** Sai giới hạn/kiểu/tràn số phải 400, không thành 500 hoặc âm thầm cắt trang. */
    @ParameterizedTest @ValueSource(strings = {"limit=0", "limit=-1", "limit=101", "limit=abc",
            "limit=2147483648", "offset=-1", "offset=abc", "offset=9223372036854775808"})
    void rejectsInvalidPageBeforeReading(String query) throws Exception {
        failure(get(verifier, "?" + query), 400, "INVALID_REQUEST"); verifyNoInteractions(reads);
    }

    /** Thiếu bearer bị từ chối ở security trước SQL hàng chờ. */
    @Test void rejectsMissingToken() throws Exception {
        failure(get(null, ""), 401, "UNAUTHENTICATED"); verifyNoInteractions(reads);
    }

    /** Khách có token hợp lệ không được đọc hàng chờ, dù thêm header tự xưng ADMIN. */
    @Test void rejectsCustomer() throws Exception {
        failure(get(tokens.newCustomerToken(clock), ""), 403, "ACCESS_DENIED"); verifyNoInteractions(reads);
    }

    /** STAFF đi qua namespace admin nhưng use case phải chặn trước đọc, không tin header giả vai/chủ. */
    @Test void rejectsBranchStaffBeforeReading() throws Exception {
        failure(get(tokens.branchStaffToken(clock), "?accountCode=TK-FAKE01&role=ADMIN"), 403, "ACCESS_DENIED");
        verifyNoInteractions(reads);
    }

    /** Query ngoài hợp đồng không biến endpoint thành bộ lọc chủ/chi nhánh hoặc lịch sử trạng thái. */
    @Test void extraContextCannotChangeQueueScope() throws Exception {
        var data = success(get(verifier, "?limit=100&accountCode=TK-FAKE01&branchCode=CN-FAKE01&status=VERIFIED"));
        assertEquals(expected, items(data)); verify(reads).searchPending(101, 0);
    }

    /** Lỗi persistence đi qua HTTP phải giữ 500 kín, không giả trang rỗng hoặc lộ lớp/SQL. */
    @Test void hidesInternalReadFailure() throws Exception {
        doThrow(new IllegalStateException("Forced SELECT document failure.")).when(reads).searchPending(21, 0);
        var response = get(verifier, ""); failure(response, 500, "INTERNAL_ERROR");
        for (String value : new String[]{"Forced", "SELECT", "Exception", "com.carrental"}) assertFalse(response.body().contains(value));
    }

    /** HTTP thật dưới sql-log/TRACE không lộ dữ liệu giấy tờ; response metadata không chứa các trường riêng tư. */
    @Test void queueHttpDoesNotLeakPrivateMaterial() throws Exception {
        success(get(verifier, "")); // Đồng bộ actor trước capture để chỉ quan sát lượt GET đọc.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel(); var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            var response = get(verifier, "?limit=100"); success(response);
            for (String secret : secrets) assertFalse(response.body().contains(secret), "Private material leaked into queue response.");
            for (var event : List.copyOf(logs.list)) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : secrets) assertFalse(text.contains(secret), "Private document material leaked into logs.");
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertTrue(p6.stream().anyMatch(e -> e.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** DTO map bất biến và che toString; trường enum được xuất bằng tên ổn định, không serialize domain. */
    @Test void responseIsImmutableAndRedacted() throws Exception {
        var data = success(get(verifier, "?limit=1"));
        var dto = json.treeToValue(data, PendingDocumentsResponse.class);
        assertEquals("PendingDocumentsResponse[redacted]", dto.toString());
        assertEquals("PendingDocumentResponse[redacted]", dto.items().getFirst().toString());
        assertThrows(UnsupportedOperationException.class, () -> dto.items().clear());
    }

    /** GET socket thật với JWT ký và requestId an toàn, không retry hoặc in bearer/payload. */
    private HttpResponse<String> get(String bearer, String query) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH + query))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .header("X-Request-Id", requestId).header("X-Role", "ADMIN").header("X-Account-Code", "TK-FAKE01");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Kiểm envelope, no-store, requestId và đúng hai trường của page; không in body khi lỗi. */
    private JsonNode success(HttpResponse<String> response) {
        assertEquals(200, response.statusCode()); headers(response);
        var body = json.readTree(response.body()); assertEquals(Set.of("success", "data", "error"), keys(body));
        assertTrue(body.path("success").asBoolean()); assertTrue(body.path("error").isNull());
        var data = body.path("data"); assertEquals(Set.of("items", "hasMore"), keys(data));
        assertTrue(data.path("items").isArray()); assertTrue(data.path("hasMore").isBoolean());
        for (var item : data.path("items")) assertEquals(Set.of("code", "accountCode", "documentType", "submittedAt"), keys(item));
        return data;
    }

    /** So đúng mã lỗi ổn định, không coi mọi 4xx/5xx là đạt. */
    private void failure(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode()); headers(response);
        var body = json.readTree(response.body()); assertFalse(body.path("success").asBoolean());
        assertTrue(body.path("data").isNull()); assertEquals(code, body.path("error").path("code").asString());
    }

    /** Cả thành công lẫn lỗi phải không cache và trả đúng ID truy vết. */
    private void headers(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    /** Đọc DTO từ JSON để so với fixture tạo trước HTTP, không dùng một adapter khác làm đáp án. */
    private List<PendingDocumentResponse> items(JsonNode data) {
        var result = new ArrayList<PendingDocumentResponse>();
        data.path("items").forEach(item -> result.add(json.treeToValue(item, PendingDocumentResponse.class)));
        return result;
    }

    /** So toàn bộ tên trường để bắt trường nội bộ bị thêm ngoài hợp đồng. */
    private static Set<String> keys(JsonNode node) {
        var names = new HashSet<String>(); node.properties().forEach(e -> names.add(e.getKey())); return names;
    }
}
