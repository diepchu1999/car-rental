package com.carrental.shared.logging;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm metadata đi qua mọi kiểu đăng ký lịch, proxy và ErrorHandler trên worker thật. */
class ContextAwareTaskSchedulerTest {
    /** Cả sáu kiểu lịch đều giữ tên method và cùng ID tới ErrorHandler, không đoán từ stack exception. */
    @ParameterizedTest
    @EnumSource(ScheduleKind.class)
    void carriesMethodContextThroughErrorHandler(ScheduleKind kind) throws Exception {
        assertMethodContext(kind, false);
    }

    /** Hồi quy: wrapper Task thật của Spring vẫn giữ tên method tới ErrorHandler ở cả sáu kiểu lịch. */
    @ParameterizedTest
    @EnumSource(ScheduleKind.class)
    void carriesWrappedMethodContextThroughErrorHandler(ScheduleKind kind) throws Exception {
        assertMethodContext(kind, true);
    }

    /** Dùng cùng kỳ vọng cho callback trực tiếp và callback bị Spring bọc, không giả tên job trong test. */
    private void assertMethodContext(ScheduleKind kind, boolean wrapped) throws Exception {
        var failure = new IllegalStateException("Expected scheduled failure");
        failure.setStackTrace(new StackTraceElement[0]);
        var invocationContext = new AtomicReference<Map<String, String>>();
        var errorContext = new AtomicReference<Map<String, String>>();
        var observedFailure = new AtomicReference<Throwable>();
        var handled = new CountDownLatch(1);
        var target = new ProbeJob(failure, invocationContext);
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        var proxy = factory.getProxy();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ScheduledJobObservationHandler());
        Runnable runnable = wrapped
                ? new Task(new ScheduledMethodRunnable(proxy, ProbeJob.class.getMethod("run"), null,
                        () -> registry)).getRunnable()
                : new ScheduledMethodRunnable(proxy, ProbeJob.class.getMethod("run"));
        var scheduler = new ContextAwareTaskScheduler();
        scheduler.setErrorHandler(error -> {
            errorContext.set(MDC.getCopyOfContextMap());
            observedFailure.set(error);
            handled.countDown();
        });
        scheduler.initialize();
        try {
            var future = schedule(scheduler, runnable, kind);
            assertNotNull(future);
            try {
                assertTrue(handled.await(5, TimeUnit.SECONDS), "The scheduled callback must reach its error handler.");
                assertSame(failure, observedFailure.get());
                assertEquals("ProbeJob.run", invocationContext.get().get("api"));
                assertEquals(invocationContext.get(), errorContext.get());
                assertTrue(errorContext.get().get("requestId").startsWith("job-"));
            } finally {
                future.cancel(false);
            }
        } finally {
            scheduler.shutdown();
        }
    }

    /** Chọn đúng overload; nhịp dài tránh lượt thứ hai thay đổi mẫu đang kiểm. */
    private static ScheduledFuture<?> schedule(ContextAwareTaskScheduler scheduler, Runnable runnable,
                                                ScheduleKind kind) {
        Duration period = Duration.ofHours(1);
        return switch (kind) {
            case TRIGGER -> scheduler.schedule(runnable,
                    context -> context.lastCompletion() == null ? Instant.now() : null);
            case ONCE -> scheduler.schedule(runnable, Instant.now());
            case FIXED_RATE -> scheduler.scheduleAtFixedRate(runnable, period);
            case FIXED_RATE_WITH_START -> scheduler.scheduleAtFixedRate(runnable, Instant.now(), period);
            case FIXED_DELAY -> scheduler.scheduleWithFixedDelay(runnable, period);
            case FIXED_DELAY_WITH_START -> scheduler.scheduleWithFixedDelay(runnable, Instant.now(), period);
        };
    }

    /** Các đường đăng ký được Spring TaskScheduler hỗ trợ, không phải các kiểu nghiệp vụ. */
    private enum ScheduleKind {
        TRIGGER, ONCE, FIXED_RATE, FIXED_RATE_WITH_START, FIXED_DELAY, FIXED_DELAY_WITH_START
    }

    /** Callback có metadata method công khai, được bọc CGLIB để kiểm tên class không dính proxy. */
    public static class ProbeJob {
        private final RuntimeException failure;
        private final AtomicReference<Map<String, String>> context;

        /** Nhận lỗi và nơi lưu MDC để thread test đọc lại sau latch. */
        public ProbeJob(RuntimeException failure, AtomicReference<Map<String, String>> context) {
            this.failure = failure;
            this.context = context;
        }

        /** Chụp ngữ cảnh rồi ném nguyên lỗi đã chuẩn bị, không tự log hoặc bắt exception. */
        public void run() {
            context.set(MDC.getCopyOfContextMap());
            throw failure;
        }
    }
}
