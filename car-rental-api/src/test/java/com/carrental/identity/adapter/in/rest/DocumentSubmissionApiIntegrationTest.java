package com.carrental.identity.adapter.in.rest;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.adapter.in.rest.client.request.SubmitDocumentRequest;
import com.carrental.identity.adapter.in.rest.client.response.DocumentSubmissionResponse;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.identity.domain.DocumentSubmission;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.DocumentImageFixtures;
import com.carrental.storage.application.port.out.DocumentObjectPort;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** HTTP/JWT/transaction/crypto/PostgreSQL và kiểm nội dung ảnh thật; chỉ giả S3 bằng kho bytes trong bộ nhớ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DocumentSubmissionApiIntegrationTest {
    private static final String PATH = "/api/v1/client/documents";
    @LocalServerPort private int port;
    @Autowired private TestJwtIssuer tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper json;
    @Autowired private ReadDocumentUploadPort uploads;
    @MockitoSpyBean private ReadDocumentSubmissionPort reads;
    @MockitoSpyBean private WriteDocumentSubmissionPort writes;
    @MockitoBean private DocumentObjectPort objects;
    private final Map<String, byte[]> bytes = new ConcurrentHashMap<>();

    /** Kho S3 giả giữ hợp đồng đọc giới hạn; parser ảnh và luồng seal của storage không bị mock. */
    @BeforeEach void configureObjectStore() {
        bytes.clear();
        when(objects.uploadUrl(anyString(), any())).thenReturn("http://localhost/fake-upload?fake-signature=private");
        when(objects.read(anyString(), anyLong())).thenAnswer(invocation -> {
            byte[] value = bytes.get((String) invocation.getArgument(0));
            long limit = invocation.getArgument(1);
            if (value == null) throw DomainException.ruleViolation(ErrorCode.DOCUMENT_UPLOAD_NOT_READY);
            if (value.length > limit) throw DomainException.ruleViolation(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE);
            return value.clone();
        });
        doAnswer(invocation -> {
            bytes.put(invocation.getArgument(0), ((byte[]) invocation.getArgument(1)).clone()); return null;
        }).when(objects).put(anyString(), any(byte[].class), anyString());
    }

    /** Cả CCCD/GPLX trả đúng sáu trường sau commit; ảnh đã chốt khác key tạm và có bytes đã kiểm. */
    @ParameterizedTest @ValueSource(strings = {"IDENTITY_CARD", "DRIVER_LICENSE"})
    void submitsDocumentWithRealImageInspection(String type) throws Exception {
        String token = tokens.customerToken(clock);
        var body = validBody(token, type);
        Instant before = clock.instant();
        var response = post(token, PATH, json.writeValueAsString(body));
        assertEquals(201, response.statusCode()); assertPrivateResponse(response);
        assertTrue(response.headers().firstValue("Location").isEmpty());
        var envelope = json.readTree(response.body());
        assertTrue(envelope.path("success").asBoolean()); assertTrue(envelope.path("error").isNull());
        var data = envelope.path("data");
        assertEquals(6, data.size());
        if (type.equals("DRIVER_LICENSE")) assertEquals("2025-01-01", data.path("issuedOn").asString());
        else assertTrue(data.path("issuedOn").isNull());
        assertTrue(data.path("code").asString().matches("GT-[A-Z0-9]{6}"));
        assertEquals(type, data.path("documentType").asString());
        assertEquals("PENDING", data.path("status").asString());
        assertEquals("2035-01-01", data.path("expiresOn").asString());
        Instant submitted = Instant.parse(data.path("submittedAt").asString());
        assertFalse(submitted.isBefore(before.minusMillis(1))); assertFalse(submitted.isAfter(clock.instant()));
        var saved = reads.findByCodeAndOwner(data.path("code").asString(), owner(token)).orElseThrow();
        assertEquals(submitted, saved.submittedAt());
        assertEquals(type.equals("DRIVER_LICENSE") ? java.time.LocalDate.of(2025, 1, 1) : null, saved.issuedOn());
        var captured = ArgumentCaptor.forClass(DocumentSubmission.class);
        verify(writes).insert(captured.capture());
        var record = captured.getValue();
        assertEquals(owner(token), record.accountCode());
        assertArrayEquals(DocumentImageFixtures.image("png"), bytes.get(record.frontImage().sealedObjectKey()));
        assertArrayEquals(DocumentImageFixtures.image("png"), bytes.get(record.backImage().sealedObjectKey()));
        verify(objects, times(2)).put(anyString(), any(byte[].class), eq("image/png"));
        verify(objects, never()).viewUrl(anyString(), any());
        for (String hidden : new String[]{"number", "fullName", "birthDate", "objectKey", "uploadUrl", "accountId"}) {
            assertFalse(data.has(hidden));
        }
        assertFalse(response.body().contains("000000000000"));
        assertFalse(response.body().contains("LOCAL PRIVATE NAME"));
    }

    /** Xác thực chạy trước thao tác; thiếu token 401 và token ADMIN không có CUSTOMER là 403. */
    @Test void rejectsMissingAndStaffTokens() throws Exception {
        assertFailure(post(null, PATH, "{}"), 401, "UNAUTHENTICATED");
        assertFailure(post(tokens.adminToken(clock), PATH, "{}"), 403, "ACCESS_DENIED");
        verifyNoInteractions(objects, writes);
    }

    /** Thiếu/null mỗi trường CCCD và ngày sai đều 400 trước chốt ảnh hoặc lưu. */
    @ParameterizedTest @ValueSource(strings = {"documentType", "number", "fullName", "birthDate", "expiresOn", "frontUploadCode", "backUploadCode"})
    void rejectsMissingAndNullRequiredFields(String field) throws Exception {
        String token = tokens.customerToken(clock);
        var body = shape("IDENTITY_CARD", "AN-FRONT1", "AN-BACK01");
        body.remove(field);
        assertFailure(post(token, PATH, json.writeValueAsString(body)), 400, "INVALID_REQUEST");
        body.put(field, null);
        assertFailure(post(token, PATH, json.writeValueAsString(body)), 400, "INVALID_REQUEST");
        verifyNoInteractions(objects, writes);
    }

    /** GPLX thiếu Boolean không được tự biến thành false; false hợp lệ đã có ở test thành công. */
    @Test void requiresExplicitDriverLicenseFields() throws Exception {
        String token = tokens.customerToken(clock);
        for (String field : new String[]{"licenseClass", "automaticOnly", "issuedOn"}) {
            var body = shape("DRIVER_LICENSE", "AN-FRONT1", "AN-BACK01"); body.remove(field);
            assertFailure(post(token, PATH, json.writeValueAsString(body)), 400, "INVALID_REQUEST");
            body.put(field, null);
            assertFailure(post(token, PATH, json.writeValueAsString(body)), 400, "INVALID_REQUEST");
        }
        verifyNoInteractions(objects, writes);
    }

    /** BR-130: ngày cấp sai bị 400 trước khi đọc hoặc chốt ảnh, không lộ giá trị đã nhập. */
    @ParameterizedTest @ValueSource(strings = {"2036-01-01", "private-invalid", "", "2025-02-29", "tomorrow"})
    void rejectsInvalidIssueDateBeforeStorage(String date) throws Exception {
        String token = tokens.customerToken(clock);
        var body = shape("DRIVER_LICENSE", "AN-FRONT1", "AN-BACK01");
        body.put("issuedOn", date.equals("tomorrow")
                ? java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))).plusDays(1).toString() : date);
        assertFailure(post(token, PATH, json.writeValueAsString(body)), 400, "INVALID_REQUEST");
        verifyNoInteractions(objects, writes);
    }

    /** Chủ, trạng thái, người duyệt, ID/key/URL do client khai không được âm thầm bỏ qua. */
    @ParameterizedTest @ValueSource(strings = {"accountCode", "status", "reviewerAccountCode", "id", "objectKey", "uploadUrl"})
    void rejectsClientControlledFields(String field) throws Exception {
        String token = tokens.customerToken(clock);
        var body = validBody(token, "IDENTITY_CARD"); body.put(field, "PRIVATE-INJECTED-VALUE");
        clearInvocations(objects);
        var response = post(token, PATH, json.writeValueAsString(body));
        assertFailure(response, 400, "INVALID_REQUEST");
        assertFalse(response.body().contains("PRIVATE-INJECTED-VALUE"));
        verifyNoInteractions(objects, writes);
    }

    /** Dữ liệu sai định dạng, thiếu body và JSON sai không làm lộ dữ liệu/parser trong lỗi. */
    @Test void rejectsMalformedInputWithoutEchoingIt() throws Exception {
        String token = tokens.customerToken(clock);
        assertFailure(post(token, PATH, ""), 400, "INVALID_REQUEST");
        assertFailure(post(token, PATH, "{\"number\":\"PRIVATE"), 400, "INVALID_REQUEST");
        for (String field : new String[]{"documentType", "birthDate", "expiresOn", "frontUploadCode"}) {
            var body = shape("IDENTITY_CARD", "AN-FRONT1", "AN-BACK01"); body.put(field, "PRIVATE-INVALID");
            var response = post(token, PATH, json.writeValueAsString(body));
            assertFailure(response, 400, "INVALID_REQUEST"); assertFalse(response.body().contains("PRIVATE-INVALID"));
        }
        var same = shape("IDENTITY_CARD", "AN-FRONT1", "AN-FRONT1");
        assertFailure(post(token, PATH, json.writeValueAsString(same)), 400, "INVALID_REQUEST");
        verifyNoInteractions(objects, writes);
    }

    /** JWT khách B không thể dùng AN của A; kiểm cả hai vị trí trước đọc bất kỳ object nào. */
    @ParameterizedTest @ValueSource(strings = {"frontUploadCode", "backUploadCode"})
    void rejectsOtherCustomersUpload(String field) throws Exception {
        String first = tokens.customerToken(clock), second = tokens.anotherCustomerToken(clock);
        var a = validBody(first, "IDENTITY_CARD"); var b = validBody(second, "IDENTITY_CARD");
        b.put(field, a.get(field)); clearInvocations(objects);
        assertFailure(post(second, PATH, json.writeValueAsString(b)), 404, "DOCUMENT_UPLOAD_NOT_FOUND");
        verifyNoInteractions(objects, writes);
    }

    /** AN đúng chủ nhưng sai loại hoặc đảo mặt đều 400 trước storage. */
    @Test void rejectsWrongDocumentTypeAndSides() throws Exception {
        String token = tokens.customerToken(clock);
        var card = validBody(token, "IDENTITY_CARD");
        var driver = shape("DRIVER_LICENSE", (String) card.get("frontUploadCode"), (String) card.get("backUploadCode"));
        clearInvocations(objects);
        assertFailure(post(token, PATH, json.writeValueAsString(driver)), 400, "INVALID_REQUEST");
        Object front = card.get("frontUploadCode"); card.put("frontUploadCode", card.get("backUploadCode")); card.put("backUploadCode", front);
        assertFailure(post(token, PATH, json.writeValueAsString(card)), 400, "INVALID_REQUEST");
        verifyNoInteractions(objects, writes);
    }

    /** Lặp cùng hai AN trả 409 đúng mã, không tạo hồ sơ thứ hai hoặc thử GT khác. */
    @Test void rejectsReusingCommittedUploads() throws Exception {
        String token = tokens.customerToken(clock);
        var body = validBody(token, "IDENTITY_CARD");
        assertEquals(201, post(token, PATH, json.writeValueAsString(body)).statusCode());
        clearInvocations(writes);
        assertFailure(post(token, PATH, json.writeValueAsString(body)), 409, "DOCUMENT_UPLOAD_ALREADY_USED");
        verify(writes, times(1)).insert(any());
    }

    /** Ảnh chưa tải, 6 MB và văn bản giả ảnh trả đúng 422; file giả được parser storage thật từ chối. */
    @ParameterizedTest @ValueSource(strings = {"missing", "large", "invalid"})
    void rejectsInvalidImageContent(String variant) throws Exception {
        String token = tokens.customerToken(clock);
        var body = validBody(token, "IDENTITY_CARD");
        String key = uploads.findByCodeAndOwner((String) body.get("frontUploadCode"), owner(token)).orElseThrow().incomingObjectKey();
        String code;
        switch (variant) {
            case "missing" -> { bytes.remove(key); code = "DOCUMENT_UPLOAD_NOT_READY"; }
            case "large" -> { bytes.put(key, new byte[6000000]); code = "DOCUMENT_IMAGE_TOO_LARGE"; }
            case "invalid" -> { bytes.put(key, "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)); code = "DOCUMENT_IMAGE_INVALID"; }
            default -> throw new IllegalArgumentException("Unknown fixture variant.");
        }
        assertFailure(post(token, PATH, json.writeValueAsString(body)), 422, code);
        verifyNoInteractions(writes);
    }

    /** Lỗi reload qua HTTP trả 500 kín và rollback thật; không lộ số, key, SQL hay tên lớp. */
    @Test void hidesInternalDetailsAndRollsBackOnReloadFailure() throws Exception {
        String token = tokens.customerToken(clock);
        var body = validBody(token, "IDENTITY_CARD");
        doReturn(Optional.empty()).when(reads).findByCodeAndOwner(anyString(), anyString());
        var response = post(token, PATH, json.writeValueAsString(body));
        assertFailure(response, 500, "INTERNAL_ERROR");
        for (String hidden : new String[]{"000000000000", "LOCAL PRIVATE NAME", "sealed/", "incoming/", "SELECT", "DocumentSubmission"}) {
            assertFalse(response.body().contains(hidden));
        }
        var captured = ArgumentCaptor.forClass(DocumentSubmission.class); verify(writes).insert(captured.capture());
        reset(reads);
        var saved = captured.getValue();
        assertTrue(reads.findByCodeAndOwner(saved.code(), saved.accountCode()).isEmpty());
    }

    /** DTO không tự in số/họ tên hay metadata hồ sơ qua toString mặc định của record. */
    @Test void redactsDtoStrings() {
        assertEquals("SubmitDocumentRequest[redacted]", new SubmitDocumentRequest("IDENTITY_CARD", "PRIVATE",
                "PRIVATE NAME", "1990-01-01", "2035-01-01", null, null, "AN-FRONT1", "AN-BACK01", null).toString());
        assertEquals("DocumentSubmissionResponse[redacted]", new DocumentSubmissionResponse("GT-LOCAL1", "IDENTITY_CARD",
                "PENDING", java.time.LocalDate.of(2035, 1, 1), Instant.EPOCH, null).toString());
    }

    /** Cấp AN qua HTTP thật; đặt bytes ở biên S3 giả, không bỏ qua quan hệ chủ/loại/mặt trong PostgreSQL. */
    private Map<String, Object> validBody(String token, String type) throws Exception {
        String front = createUpload(token, type, "FRONT"), back = createUpload(token, type, "BACK");
        String owner = owner(token);
        for (String code : new String[]{front, back}) {
            var upload = uploads.findByCodeAndOwner(code, owner).orElseThrow();
            bytes.put(upload.incomingObjectKey(), DocumentImageFixtures.image("png"));
        }
        return shape(type, front, back);
    }

    /** Dữ liệu khai thử; không có số/ảnh giấy tờ thật hoặc trường chủ do client đặt. */
    private static Map<String, Object> shape(String type, String front, String back) {
        var body = new LinkedHashMap<String, Object>();
        body.put("documentType", type); body.put("number", "000000000000"); body.put("expiresOn", "2035-01-01");
        body.put("frontUploadCode", front); body.put("backUploadCode", back);
        if (type.equals("IDENTITY_CARD")) { body.put("fullName", "LOCAL PRIVATE NAME"); body.put("birthDate", "1990-01-01"); }
        else { body.put("licenseClass", "B"); body.put("automaticOnly", false); body.put("issuedOn", "2025-01-01"); }
        return body;
    }

    /** Upload URL cấp bởi service thật, response không được in ra log. */
    private String createUpload(String token, String type, String side) throws Exception {
        var response = post(token, "/api/v1/client/document-uploads", json.writeValueAsString(Map.of("documentType", type, "side", side)));
        assertEquals(201, response.statusCode()); return json.readTree(response.body()).path("data").path("code").asString();
    }

    /** Danh tính lấy qua /me với JWT thật, không gán accountCode trong body nộp. */
    private String owner(String token) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/client/accounts/me"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); return json.readTree(response.body()).path("data").path("code").asString();
        }
    }

    /** Socket HTTP thật, giữ header chuẩn; không log token hoặc request/response chứa PII. */
    private HttpResponse<String> post(String token, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try (var http = HttpClient.newHttpClient()) { return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()); }
    }

    /** Phải đúng mã HTTP/mã nghiệp vụ và envelope kín, không coi lỗi bất kỳ là đạt. */
    private void assertFailure(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode()); assertPrivateResponse(response);
        var body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean()); assertTrue(body.path("data").isNull());
        assertEquals(code, body.path("error").path("code").asString());
    }

    /** Cả thành công/lỗi đều giữ requestId và không cache dữ liệu của khách. */
    private static void assertPrivateResponse(HttpResponse<String> response) {
        assertTrue(response.headers().firstValue("X-Request-Id").orElse("").matches("[A-Za-z0-9-]{1,64}"));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }
}
