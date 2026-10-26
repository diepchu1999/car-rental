package com.carrental.identity.adapter.in.rest;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.UploadRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** BR-120/130: HTTP, JWT và PostgreSQL thật; chỉ thay biên ký URL, không thay use case/transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentUploadApiIntegrationTest {
    private static final String PATH = "/api/v1/client/document-uploads";
    private static final String VALID_BODY = "{\"documentType\":\"IDENTITY_CARD\",\"side\":\"FRONT\"}";
    @LocalServerPort private int port;
    @Autowired private TestJwtIssuer tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @MockitoBean private DocumentStorageDirectory storage;
    @MockitoSpyBean private WriteDocumentUploadPort writes;
    @MockitoSpyBean private ReadDocumentUploadPort reads;

    /** URL giả không truy cập MinIO local; adapter MinIO thật có bộ integration test riêng. */
    @BeforeEach void configureSignedLink() {
        when(storage.createUpload()).thenAnswer(invocation -> new UploadRef("incoming/" + UUID.randomUUID(),
                "http://localhost/fake-upload?fake-signature=private", clock.instant().plusSeconds(300)));
    }

    /** Cả bốn cặp loại/mặt đều lưu chủ JWT, trả AN và hạn; không trả ID số/key thành trường riêng. */
    @Test void createsEachSideForAuthenticatedOwner() throws Exception {
        String token = tokens.customerToken(clock);
        String owner = currentAccountCode(token);
        for (String type : new String[]{"IDENTITY_CARD", "DRIVER_LICENSE"}) {
            for (String side : new String[]{"FRONT", "BACK"}) {
                Instant before = clock.instant();
                var response = post(token, "{\"documentType\":\"" + type + "\",\"side\":\"" + side + "\"}");
                assertEquals(201, response.statusCode());
                assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
                assertRequestId(response);
                var body = json.readTree(response.body());
                assertTrue(body.path("success").asBoolean());
                assertTrue(body.path("error").isNull());
                var data = body.path("data");
                assertEquals(5, data.size());
                String code = data.path("code").asString();
                assertTrue(code.matches("AN-[A-Z0-9]{6}"));
                assertEquals(type, data.path("documentType").asString());
                assertEquals(side, data.path("side").asString());
                assertTrue(data.path("uploadUrl").asString().startsWith("http://localhost/fake-upload?"));
                Instant expiresAt = Instant.parse(data.path("expiresAt").asString());
                assertTrue(expiresAt.isAfter(before.plusSeconds(299)));
                assertFalse(expiresAt.isAfter(clock.instant().plusSeconds(300)));
                var saved = reads.findByCodeAndOwner(code, owner).orElseThrow();
                assertEquals(type, saved.documentType().name());
                assertEquals(side, saved.side().name());
                assertEquals(expiresAt, saved.expiresAt());
                assertTrue(reads.findByCodeAndOwner(code, "TK-OTHER1").isEmpty());
            }
        }
        verify(writes, times(4)).insert(any());
        verify(storage, times(4)).createUpload();
    }

    /** Thiếu/sai trường và cố truyền chủ/key/hạn đều 400 trước khi ký hoặc ghi. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"side\":\"FRONT\"}", "{\"documentType\":\"IDENTITY_CARD\"}",
            "{\"documentType\":null,\"side\":\"FRONT\"}",
            "{\"documentType\":\"IDENTITY_CARD\",\"side\":null}",
            "{\"documentType\":\"PASSPORT\",\"side\":\"FRONT\"}",
            "{\"documentType\":\"IDENTITY_CARD\",\"side\":\"front\"}",
            "{\"documentType\":\"IDENTITY_CARD\",\"side\":\"FRONT\",\"accountCode\":\"TK-OTHER1\"}",
            "{\"documentType\":\"IDENTITY_CARD\",\"side\":\"FRONT\",\"objectKey\":\"incoming/foreign\"}",
            "{\"documentType\":\"IDENTITY_CARD\",\"side\":\"FRONT\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"
    })
    void rejectsInvalidOrClientControlledFields(String body) throws Exception {
        assertFailure(post(tokens.customerToken(clock), body), 400, "INVALID_REQUEST");
        verifyNoInteractions(storage, writes);
    }

    /** Không token thì 401, không tạo lượt tải vô chủ. */
    @Test void rejectsMissingToken() throws Exception {
        assertFailure(post(null, VALID_BODY), 401, "UNAUTHENTICATED");
        verifyNoInteractions(storage, writes);
    }

    /** Token nhân viên ADMIN không cấp quyền gọi namespace khách. */
    @Test void rejectsStaffToken() throws Exception {
        assertFailure(post(tokens.adminToken(clock), VALID_BODY), 403, "ACCESS_DENIED");
        verifyNoInteractions(storage, writes);
    }

    /** Lỗi sau INSERT phải rollback thật và không trả URL, key hoặc chi tiết nội bộ trong JSON. */
    @Test void rollsBackInsertWhenReloadFails() throws Exception {
        doReturn(Optional.empty()).when(reads).findByCodeAndOwner(anyString(), anyString());
        var response = post(tokens.customerToken(clock), VALID_BODY);
        assertFailure(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().contains("fake-signature"));
        assertFalse(response.body().contains("incoming/"));
        assertFalse(response.body().contains("DocumentUploadCommandService"));
        var captured = ArgumentCaptor.forClass(DocumentUpload.class);
        verify(writes, times(1)).insert(captured.capture());
        doCallRealMethod().when(reads).findByCodeAndOwner(anyString(), anyString());
        var upload = captured.getValue();
        assertTrue(reads.findByCodeAndOwner(upload.code(), upload.accountCode()).isEmpty());
    }

    /** Lấy mã chủ từ API thật, không tự gán chủ cho request upload. */
    private String currentAccountCode(String token) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/client/accounts/me"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return json.readTree(response.body()).path("data").path("code").asString();
        }
    }

    /** Gửi JSON qua socket HTTP thật; không ghi token hay response chứa URL vào log test. */
    private HttpResponse<String> post(String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Kiểm đúng HTTP và mã lỗi, không chỉ kiểm rằng có lỗi bất kỳ. */
    private void assertFailure(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertRequestId(response);
        var body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean());
        assertTrue(body.path("data").isNull());
        assertEquals(code, body.path("error").path("code").asString());
    }

    /** Giữ hợp đồng truy vết cho cả response thành công lẫn lỗi. */
    private static void assertRequestId(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("X-Request-Id").orElse("").matches("[A-Za-z0-9-]{1,64}"));
    }
}
