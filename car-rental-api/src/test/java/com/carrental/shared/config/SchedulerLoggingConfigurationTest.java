package com.carrental.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.carrental.shared.logging.LogEventCapture;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm Boot thực sự áp customizer và callback định kỳ dùng ID/log mới mà không dừng sau lỗi. */
class SchedulerLoggingConfigurationTest {

    /** Timer thật: lỗi đầu ghi một lần với cùng ID của job, lượt sau thành công và dùng ID mới. */
    @Test
    void bootSchedulerLogsFailureOnceAndContinuesWithNewId() {
        var ids = new CopyOnWriteArrayList<String>();
        var calls = new AtomicInteger();
        var nextSucceeded = new CountDownLatch(1);
        var failure = new IllegalStateException("Expected scheduler probe failure");
        try (var logs = new LogEventCapture(Logger.ROOT_LOGGER_NAME)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                    .withUserConfiguration(SchedulerLoggingConfiguration.class, SchedulingEnabled.class)
                    .withPropertyValues("spring.threads.virtual.enabled=false", "spring.task.scheduling.pool.size=1")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var scheduler = context.getBean(TaskScheduler.class);
                        assertInstanceOf(ThreadPoolTaskScheduler.class, scheduler);
                        var future = scheduler.scheduleWithFixedDelay(() -> {
                            ids.add(MDC.get("requestId"));
                            LoggerFactory.getLogger("com.carrental.scheduler.probe").info("Scheduler probe event");
                            if (calls.incrementAndGet() == 1) {
                                throw failure;
                            }
                            nextSucceeded.countDown();
                        }, Duration.ofMillis(50));
                        try {
                            assertTrue(nextSucceeded.await(5, TimeUnit.SECONDS), "The next scheduled invocation must run.");
                            assertFalse(future.isDone(), "A repeating task must survive its failed invocation.");
                        } finally {
                            future.cancel(false);
                        }
                        var completedIds = List.copyOf(ids);
                        assertTrue(completedIds.size() >= 2);
                        completedIds.forEach(id -> {
                            assertNotNull(id);
                            assertTrue(id.startsWith("job-"));
                            assertEquals(id.substring(4), UUID.fromString(id.substring(4)).toString());
                        });
                        assertEquals(completedIds.size(), completedIds.stream().distinct().count());
                        var errors = logs.events().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
                        assertEquals(1, errors.size(), "Neither the default scheduler nor the decorator may log a duplicate.");
                        var event = errors.getFirst();
                        assertEquals(SchedulerLoggingConfiguration.class.getName(), event.getLoggerName());
                        assertSame(failure, ((ThrowableProxy) event.getThrowableProxy()).getThrowable());
                        assertEquals(ids.getFirst(), event.getMDCPropertyMap().get("requestId"));
                        assertTrue(event.getFormattedMessage().contains("method=\"-\" path=\"-\""));
                        assertTrue(event.getFormattedMessage().contains("requestId=\"" + ids.getFirst() + "\""));
                        assertTrue(logs.events().stream().anyMatch(probe -> probe.getFormattedMessage().equals("Scheduler probe event")
                                && ids.getFirst().equals(probe.getMDCPropertyMap().get("requestId"))));
                    });
        }
    }

    /** Chỉ bật hạ tầng scheduler trong context test, không tạo timer hoặc dữ liệu nghiệp vụ giả. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {
    }
}
