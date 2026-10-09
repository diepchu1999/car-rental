package com.carrental.shared.logging;

import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hook chỉ sở hữu ngữ cảnh job, không nhận nhầm HTTP hoặc làm rò ThreadLocal sau lỗi. */
class ScheduledJobObservationHandlerTest {
    private final ScheduledJobObservationHandler handler = new ScheduledJobObservationHandler();
    private Map<String, String> previousContext;

    /** Cách ly MDC của thread JUnit trước mỗi phép thử. */
    @BeforeEach
    void saveContext() {
        previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    /** Trả lại MDC của test bên ngoài. */
    @AfterEach
    void restoreContext() {
        MDC.clear();
        if (previousContext != null) {
            MDC.setContextMap(previousContext);
        }
    }

    /** Observation thông thường không được gắn nhãn job. */
    @Test
    void supportsOnlyScheduledMethodContext() throws Exception {
        assertTrue(handler.supportsContext(jobContext()));
        assertFalse(handler.supportsContext(new Observation.Context()));
    }

    /** Tiền tố job- do client tự gửi không cho phép handler thay nhãn HTTP khi gọi tay method. */
    @Test
    void ignoresMethodObservationOutsideManagedJob() throws Exception {
        MDC.put("requestId", "job-client-supplied");
        MDC.put("api", "GET /probe");
        handler.onStart(jobContext());
        assertEquals(Map.of("requestId", "job-client-supplied", "api", "GET /probe"), MDC.getCopyOfContextMap());
        assertFalse(JobLoggingTaskDecorator.isJobExecution());
    }

    /** Observation dừng không mất tên trước ErrorHandler; decorator khôi phục context khi lỗi truyền ra. */
    @Test
    void retainsIdentityUntilDecoratorRestoresContextOnFailure() throws Exception {
        MDC.put("requestId", "outer-id");
        MDC.put("api", "GET /outer");
        var context = jobContext();
        var failure = new IllegalStateException("Expected job failure");
        Runnable callback = new JobLoggingTaskDecorator().decorate(() -> {
            assertTrue(JobLoggingTaskDecorator.isJobExecution());
            handler.onStart(context);
            handler.onStop(context);
            assertEquals("ProbeJob.run", MDC.get("api"));
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, callback::run));
        assertEquals(Map.of("requestId", "outer-id", "api", "GET /outer"), MDC.getCopyOfContextMap());
        assertFalse(JobLoggingTaskDecorator.isJobExecution());
    }

    /** Decorator lồng nhau trả lại đúng phạm vi ngoài rồi dọn marker khi hoàn tất. */
    @Test
    void restoresNestedJobScopeAfterSuccess() {
        var decorator = new JobLoggingTaskDecorator();
        decorator.decorate(() -> {
            String outerId = MDC.get("requestId");
            MDC.put("api", "OuterJob.run");
            decorator.decorate(() -> {
                assertTrue(JobLoggingTaskDecorator.isJobExecution());
                assertNotEquals(outerId, MDC.get("requestId"));
                MDC.put("api", "InnerJob.run");
            }).run();
            assertTrue(JobLoggingTaskDecorator.isJobExecution());
            assertEquals(outerId, MDC.get("requestId"));
            assertEquals("OuterJob.run", MDC.get("api"));
        }).run();
        assertFalse(JobLoggingTaskDecorator.isJobExecution());
        assertNull(MDC.get("requestId"));
        assertNull(MDC.get("api"));
    }

    /** Lấy metadata public giống context do ScheduledMethodRunnable tạo. */
    private static ScheduledTaskObservationContext jobContext() throws Exception {
        return new ScheduledTaskObservationContext(new ProbeJob(), ProbeJob.class.getMethod("run"));
    }

    /** Method rỗng dùng làm nguồn metadata, không chứa xử lý nghiệp vụ. */
    public static class ProbeJob {
        /** Không cần thực thi method để kiểm metadata trong handler. */
        public void run() {
        }
    }
}
