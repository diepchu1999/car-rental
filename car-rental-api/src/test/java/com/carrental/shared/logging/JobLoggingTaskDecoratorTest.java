package com.carrental.shared.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm ID sinh theo từng lượt chạy, không phải một ID dùng suốt vòng đời job. */
class JobLoggingTaskDecoratorTest {
    private Map<String, String> previous;

    /** Cách ly ngữ cảnh của worker JUnit. */
    @BeforeEach
    void saveContext() {
        previous = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    /** Trả lại MDC trước test. */
    @AfterEach
    void restoreContext() {
        MDC.clear();
        if (previous != null) {
            MDC.setContextMap(previous);
        }
    }

    /** Cùng một callback được chạy hai lần phải có hai job-UUID riêng và không rò MDC. */
    @Test
    void generatesNewIdForEveryInvocationOfSameCallback() {
        var ids = new ArrayList<String>();
        Runnable task = new JobLoggingTaskDecorator().decorate(() -> ids.add(MDC.get(RequestIdFilter.MDC_KEY)));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        task.run();
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        task.run();
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        assertEquals(2, ids.size());
        ids.forEach(JobLoggingTaskDecoratorTest::assertJobId);
        assertNotEquals(ids.getFirst(), ids.getLast());
    }

    /** Job không dùng ID HTTP bên ngoài và khôi phục cả ngữ cảnh sau khi hoàn tất. */
    @Test
    void restoresOuterContextAfterSuccess() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-request");
        MDC.put("other", "preserved");
        new JobLoggingTaskDecorator().decorate(() -> {
            assertJobId(MDC.get(RequestIdFilter.MDC_KEY));
            assertEquals("preserved", MDC.get("other"));
        }).run();
        assertEquals(Map.of("requestId", "outer-request", "other", "preserved"), MDC.getCopyOfContextMap());
    }

    /** Callback lỗi không bị decorator nuốt, đổi đối tượng lỗi hoặc làm rò ID job. */
    @Test
    void restoresContextAndPropagatesOriginalFailure() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-request");
        var failure = new IllegalStateException("Expected failure");
        Runnable task = new JobLoggingTaskDecorator().decorate(() -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, task::run));
        assertEquals("outer-request", MDC.get(RequestIdFilter.MDC_KEY));
        MDC.clear();
        assertSame(failure, assertThrows(IllegalStateException.class, task::run));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** Kiểm ID đúng tiền tố và UUID chuẩn. */
    private static void assertJobId(String value) {
        assertNotNull(value);
        assertTrue(value.startsWith("job-"));
        assertEquals(value.substring(4), UUID.fromString(value.substring(4)).toString());
    }
}
