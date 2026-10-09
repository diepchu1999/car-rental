package com.carrental.shared.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.carrental.shared.logging.LogEventCapture;
import com.carrental.shared.logging.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm handler thật ghi đúng một tóm tắt, giữ nguyên throwable và không dump dữ liệu HTTP. */
class ApiFailureLoggingTest {

    /** Root/frame/method/path/ID đều có; query, body, token và header tùy ý không được đưa vào sự kiện lỗi. */
    @Test
    void logsOneSummaryWithOriginalThrowableAndNoRequestPayload() throws Exception {
        var root = new IllegalArgumentException("Root\r\nFORGED\tmessage");
        root.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.Driver", "execute", "Driver.java", 12),
                new StackTraceElement("com.carrental.vehicle.Probe", "save", "Probe.java", 42)
        });
        var failure = new IllegalStateException("Wrapper detail", root);
        var mvc = MockMvcBuilders.standaloneSetup(new FailureController(failure))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        try (var logs = new LogEventCapture(ApiExceptionHandler.class.getName())) {
            mvc.perform(post("/test/failure-log?secret=query-sentinel")
                            .header("X-Request-Id", "client-failure-123")
                            .header("Authorization", "Bearer token-sentinel")
                            .header("X-Private", "header-sentinel")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"private\":\"body-sentinel\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string("X-Request-Id", "client-failure-123"))
                    .andExpect(content().json("""
                            {"success":false,"data":null,"error":{"code":"INTERNAL_ERROR",
                             "message":"An unexpected error occurred."}}
                            """, JsonCompareMode.STRICT));
            assertEquals(1, logs.events().size(), "Exactly one error event must precede its original stack trace.");
            var event = logs.events().getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            String summary = event.getFormattedMessage();
            assertEquals("Failure summary: rootType=\"java.lang.IllegalArgumentException\""
                    + " rootMessage=\"Root\\r\\nFORGED\\tmessage\""
                    + " source=\"com.carrental.vehicle.Probe.save(Probe.java:42)\""
                    + " method=\"POST\" path=\"/test/failure-log\" requestId=\"client-failure-123\""
                    + " api=\"POST /test/failure-log\"", summary);
            assertEquals("client-failure-123", event.getMDCPropertyMap().get("requestId"));
            assertEquals("POST /test/failure-log", event.getMDCPropertyMap().get("api"));
            assertSame(failure, ((ThrowableProxy) event.getThrowableProxy()).getThrowable());
            for (String secret : new String[] {"query-sentinel", "token-sentinel", "header-sentinel", "body-sentinel"}) {
                assertFalse(summary.contains(secret));
                assertFalse(event.getMDCPropertyMap().toString().contains(secret));
            }
            assertEquals("Root\r\nFORGED\tmessage", root.getMessage());
        }
    }

    /** Lỗi nghiệp vụ 400 không bị nâng thành lỗi máy chủ hoặc sinh bản tóm tắt ERROR. */
    @Test
    void doesNotLogBusinessFailureAsServerError() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureController(DomainException.invalidInput("Invalid input.")))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestIdFilter()).build();
        try (var logs = new LogEventCapture(ApiExceptionHandler.class.getName())) {
            mvc.perform(post("/test/failure-log").header("X-Request-Id", "client-invalid-123"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("X-Request-Id", "client-invalid-123"))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            assertTrue(logs.events().isEmpty());
        }
    }

    /** Controller chỉ được đăng ký trực tiếp trong MockMvc, không có endpoint lỗi thử ở production. */
    @TestComponent
    @RestController
    static class FailureController {
        private final RuntimeException failure;

        /** Nhận lỗi mẫu để kiểm wrapper, nguyên nhân gốc và lỗi có chủ đích. */
        FailureController(RuntimeException failure) {
            this.failure = failure;
        }

        /** Ném lỗi mẫu mà không đọc hoặc phản chiếu body, header hay query. */
        @PostMapping("/test/failure-log")
        void fail() {
            throw failure;
        }
    }
}
