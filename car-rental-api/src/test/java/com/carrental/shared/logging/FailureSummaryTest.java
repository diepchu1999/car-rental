package com.carrental.shared.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm định dạng tóm tắt độc lập, gồm cause sâu, frame thiếu và chèn dòng giả. */
class FailureSummaryTest {
    private Map<String, String> previous;

    /** Cách ly MDC của test và đặt ID mẫu an toàn. */
    @BeforeEach
    void setContext() {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put(RequestIdFilter.MDC_KEY, "test-request-123");
    }

    /** Trả lại MDC của thread chạy test. */
    @AfterEach
    void restoreContext() {
        MDC.clear();
        if (previous != null) {
            MDC.setContextMap(previous);
        }
    }

    /** Chọn cause sâu nhất và frame ứng dụng đầu tiên của cause, không lấy thông tin wrapper. */
    @Test
    void selectsRootCauseAndItsFirstApplicationFrame() {
        var root = new IllegalArgumentException("Root failure");
        root.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.Driver", "read", "Driver.java", 11),
                new StackTraceElement("com.carrental.vehicle.VehicleProbe", "load", "VehicleProbe.java", 42),
                new StackTraceElement("com.carrental.vehicle.LaterProbe", "run", "LaterProbe.java", 99)
        });
        var failure = new IllegalStateException("Wrapper message", new RuntimeException("Middle", root));
        assertEquals("Failure summary: rootType=\"java.lang.IllegalArgumentException\""
                + " rootMessage=\"Root failure\""
                + " source=\"com.carrental.vehicle.VehicleProbe.load(VehicleProbe.java:42)\""
                + " method=\"POST\" path=\"/api/v1/admin/vehicles\" requestId=\"test-request-123\"",
                FailureSummary.format(failure, "POST", "/api/v1/admin/vehicles"));
        assertSame(root, failure.getCause().getCause());
        assertEquals(3, root.getStackTrace().length);
    }

    /** Không giả vị trí ở wrapper khi root không có frame com.carrental; null có ký hiệu rõ ràng. */
    @Test
    void marksMissingRootFrameMessageAndContextExplicitly() {
        MDC.clear();
        var root = new IllegalArgumentException();
        root.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.carrentalfake.Driver", "run", "Driver.java", 1)
        });
        var failure = new IllegalStateException("Wrapper", root);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.carrental.Wrapper", "call", "Wrapper.java", 2)
        });
        assertEquals("Failure summary: rootType=\"java.lang.IllegalArgumentException\" rootMessage=\"-\""
                + " source=\"-\" method=\"-\" path=\"-\" requestId=\"-\"",
                FailureSummary.format(failure, null, null));
    }

    /** CR/LF, tab, ANSI và ngắt dòng Unicode đều bị escape; exception/stack trace không bị thay đổi. */
    @Test
    void escapesControlCharactersWithoutMutatingThrowable() {
        String message = "Bad\r\nFORGED\t\u001b[31m\u0085\u2028\u2029\u202e\"\\";
        var failure = new IllegalStateException(message);
        StackTraceElement[] originalStack = failure.getStackTrace();
        MDC.put(RequestIdFilter.MDC_KEY, "internal\ncontext");
        String summary = FailureSummary.format(failure, "GET\r", "/path\nforged");
        assertTrue(summary.contains("Bad\\r\\nFORGED\\t\\u001b[31m\\u0085\\u2028\\u2029\\u202e\\\"\\\\"));
        assertTrue(summary.contains("method=\"GET\\r\" path=\"/path\\nforged\""));
        assertTrue(summary.contains("requestId=\"internal\\ncontext\""));
        assertFalse(summary.codePoints().anyMatch(value -> Character.isISOControl(value)
                || Character.getType(value) == Character.LINE_SEPARATOR
                || Character.getType(value) == Character.PARAGRAPH_SEPARATOR
                || Character.getType(value) == Character.FORMAT));
        assertEquals(message, failure.getMessage());
        assertArrayEquals(originalStack, failure.getStackTrace());
    }

    /** Cause tạo vòng lặp vẫn kết thúc và chọn cause cuối chưa lặp, không gây lỗi thứ cấp. */
    @Test
    void terminatesForCyclicCauseChain() {
        var first = new IllegalStateException("First");
        var second = new IllegalArgumentException("Second");
        first.initCause(second);
        second.initCause(first);
        assertTrue(FailureSummary.format(first, "-", "-").contains("rootMessage=\"Second\""));
    }

    /** Frame thiếu thông tin debug được mô tả rõ, không ném lỗi khi đang xử lý lỗi khác. */
    @Test
    void handlesFramesWithoutSourceMetadata() {
        var failure = new IllegalStateException("No source");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.carrental.Probe", "run", null, -1)
        });
        assertTrue(FailureSummary.format(failure, "-", "-").contains("com.carrental.Probe.run(Unknown Source)"));
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.carrental.Probe", "run", "Probe.java", -2)
        });
        assertTrue(FailureSummary.format(failure, "-", "-").contains("com.carrental.Probe.run(Native Method)"));
    }
}
