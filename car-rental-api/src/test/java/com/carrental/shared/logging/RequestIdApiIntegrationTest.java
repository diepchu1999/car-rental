package com.carrental.shared.logging;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đăng ký filter và pattern của Boot qua HTTP thật; không dùng datasource local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RequestIdApiIntegrationTest.ProbeConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestIdApiIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    /** Hai logger khác nhau đều mang ID client an toàn mà không thêm trường vào JSON. */
    @Test
    void correlatesEveryLogEventAndPreservesResponseShape(CapturedOutput output) throws Exception {
        String requestId = "client-" + UUID.randomUUID();
        var response = get("/test/request-id/success", requestId);
        assertEquals(200, response.statusCode());
        assertEquals(requestId, response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow());
        assertEquals(objectMapper.readTree("{\"success\":true,\"data\":\"ok\",\"error\":null}"),
                objectMapper.readTree(response.body()));
        assertCorrelatedLog(output, requestId, "Request correlation probe first event");
        assertCorrelatedLog(output, requestId, "Request correlation probe second event");
    }

    /** Header không bắt buộc; ID UUID được trả về cả ở endpoint health ngoài namespace API. */
    @Test
    void returnsGeneratedIdWhenHeaderIsMissing() throws Exception {
        var response = get("/actuator/health", null);
        assertEquals(200, response.statusCode());
        assertGeneratedResponseId(response);
        assertEquals("UP", objectMapper.readTree(response.body()).path("status").asText());
    }

    /** Ký tự cấm và ID quá dài bị thay mới qua HTTP thật, không làm đổi kết quả endpoint. */
    @ParameterizedTest
    @ValueSource(strings = {"bad_id", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefX"})
    void replacesUnsafeHeaderOverHttp(String suppliedId) throws Exception {
        var response = get("/test/request-id/success", suppliedId);
        assertEquals(200, response.statusCode());
        assertGeneratedResponseId(response);
        assertNotEquals(suppliedId, response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow());
    }

    /** Lỗi 500 giữ header và log có ID nhưng response tuyệt đối không lộ SQL, class hoặc số dòng. */
    @Test
    void correlatesUnexpectedFailureWithoutLeakingInternalDetails(CapturedOutput output) throws Exception {
        String requestId = "failure-" + UUID.randomUUID();
        var response = get("/test/request-id/failure", requestId);
        assertEquals(500, response.statusCode());
        assertEquals(requestId, response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow());
        assertEquals(objectMapper.readTree("""
                {"success":false,"data":null,"error":{"code":"INTERNAL_ERROR",
                 "message":"An unexpected error occurred."}}
                """), objectMapper.readTree(response.body()));
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("[requestId=" + requestId + "]")
                && line.contains("ERROR")), "The server error event must contain the request ID.");
        assertTrue(output.getAll().contains("IllegalStateException: Internal SQL probe failure"));
        assertTrue(output.getAll().lines().anyMatch(line -> line.startsWith("\tat com.carrental.")),
                "Stack trace continuation must retain its standard format.");
    }

    /** Gửi HTTP tới cổng ngẫu nhiên của context test, có giới hạn thời gian chờ. */
    private HttpResponse<String> get(String path, String requestId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (requestId != null) {
            builder.header(RequestIdFilter.HEADER_NAME, requestId);
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Kiểm trực tiếp tiền tố đã render bởi cấu hình logging thật, không tự gắn pattern trong test. */
    private static void assertCorrelatedLog(CapturedOutput output, String requestId, String message) {
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains(message)
                && line.contains("[requestId=" + requestId + "]")),
                "The rendered log event must include the response request ID: " + message);
    }

    /** Header do server sinh là một UUID chuẩn, không rỗng hoặc bị gộp nhiều giá trị. */
    private static void assertGeneratedResponseId(HttpResponse<String> response) {
        assertEquals(1, response.headers().allValues(RequestIdFilter.HEADER_NAME).size());
        String value = response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow();
        assertEquals(value, UUID.fromString(value).toString());
    }

    /** Đăng ký endpoint dò chỉ trong context này, không thêm API vào ứng dụng thật. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        /** Tạo controller test để kiểm log và lỗi mà không thay đổi nghiệp vụ. */
        @Bean
        ProbeController requestIdProbeController() {
            return new ProbeController();
        }
    }

    /** TestComponent ngăn component scan thông thường thu nhận controller dò. */
    @TestComponent
    @RestController
    static class ProbeController {
        /** Phát hai sự kiện từ hai logger, không ghi bất kỳ đầu vào HTTP nào. */
        @GetMapping("/test/request-id/success")
        ApiResponse<String> success() {
            LoggerFactory.getLogger("com.carrental.requestid.probe.first")
                    .info("Request correlation probe first event");
            LoggerFactory.getLogger("com.carrental.requestid.probe.second")
                    .info("Request correlation probe second event");
            return ApiResponse.success("ok");
        }

        /** Gây lỗi nội bộ có chủ đích để kiểm handler thật vẫn giấu chi tiết. */
        @GetMapping("/test/request-id/failure")
        ApiResponse<String> failure() {
            throw new IllegalStateException("Internal SQL probe failure");
        }
    }
}
