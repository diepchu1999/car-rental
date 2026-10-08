package com.carrental.availability.adapter.in.scheduler;

import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import com.carrental.shared.logging.LogEventCapture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm adapter và wiring lịch chạy thật, không dùng datasource hoặc Docker. */
class ReservationHoldExpirySchedulerTest {

    /** Kiểm mỗi lần scheduler gọi đúng một use case, không tự xử lý nghiệp vụ. */
    @Test
    void delegatesOneSweep() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        new ReservationHoldExpiryScheduler(useCase).sweep();
        verify(useCase).expireHolds();
        verifyNoMoreInteractions(useCase);
    }

    /** Kiểm lỗi use case không bị adapter nuốt hoặc tự thử lại trong cùng lượt. */
    @Test
    void propagatesUseCaseFailure() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        var failure = new IllegalStateException("Storage unavailable.");
        when(useCase.expireHolds()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new ReservationHoldExpiryScheduler(useCase).sweep()));
        verify(useCase).expireHolds();
        verifyNoMoreInteractions(useCase);
    }

    /** Kiểm không cần công tắc bật, lấy nhịp 45 giây và đăng ký đúng một job fixed-delay. */
    @Test
    void registersConfiguredFixedDelayAndInitialDelay() {
        assertScheduledDelay("PT45S");
    }

    /** Kiểm nhịp test 24 giờ cũng là độ trễ ban đầu, job vẫn được đăng ký thay vì bị tắt. */
    @Test
    void registersLongTestIntervalWithoutRunningImmediately() {
        assertScheduledDelay("PT24H");
    }

    /** Kiểm khóa cấu hình cũ không còn tắt được adapter hoặc hạ tầng scheduler theo BR-103. */
    @Test
    void legacyDisablePropertyCannotDisableScheduling() {
        assertScheduledDelay("PT45S", "car-rental.availability.hold-sweep-enabled=false");
    }

    /** Kiểm timer thật vẫn gọi lượt sau khi lượt đầu ném lỗi; không sleep hoặc gọi tay sweep. */
    @Test
    void realSchedulerContinuesAfterFailedInvocation() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch nextSucceeded = new CountDownLatch(1);
        var ids = new CopyOnWriteArrayList<String>();
        when(useCase.expireHolds()).thenAnswer(invocation -> {
            ids.add(MDC.get("requestId"));
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Expected first sweep failure.");
            }
            nextSucceeded.countDown();
            return 0;
        });
        try (var logs = new LogEventCapture("com.carrental.shared.config.SchedulerLoggingConfiguration")) {
            runner(useCase).withPropertyValues("car-rental.availability.hold-sweep-interval=PT0.05S")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(ThreadPoolTaskScheduler.class, context.getBean(TaskScheduler.class));
                    assertTrue(nextSucceeded.await(5, TimeUnit.SECONDS), "The next scheduled sweep must run.");
                    assertTrue(calls.get() >= 2);
                    assertTrue(ids.getFirst().startsWith("job-"));
                    assertNotEquals(ids.getFirst(), ids.get(1));
                    assertEquals(1, logs.events().size());
                    assertEquals(ids.getFirst(), logs.events().getFirst().getMDCPropertyMap().get("requestId"));
                });
        }
    }

    /**
     * Kiểm đăng ký lịch qua scheduler giả, không đợi thời gian thật hoặc gọi trực tiếp phương thức sweep.
     * Callback do Spring tạo chỉ được kích hoạt sau khi đã khẳng định use case chưa chạy.
     */
    private static void assertScheduledDelay(String interval, String... extraProperties) {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(java.time.Clock.systemUTC());
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        Duration expectedDelay = Duration.parse(interval);
        Instant before = Instant.now();
        runner(useCase).withPropertyValues("car-rental.availability.hold-sweep-interval=" + interval)
                .withPropertyValues(extraProperties)
                .withBean("taskScheduler", TaskScheduler.class, () -> scheduler)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(ReservationHoldExpiryScheduler.class).size());
                    assertEquals(1, context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks().size());
                    var task = ArgumentCaptor.forClass(Runnable.class);
                    var start = ArgumentCaptor.forClass(Instant.class);
                    verify(scheduler).scheduleWithFixedDelay(task.capture(), start.capture(), eq(expectedDelay));
                    assertFalse(start.getValue().isBefore(before.plus(expectedDelay)));
                    assertFalse(start.getValue().isAfter(Instant.now().plus(expectedDelay)));
                    verifyNoInteractions(useCase);
                    task.getValue().run();
                    verify(useCase).expireHolds();
                    verifyNoMoreInteractions(useCase);
                });
    }

    /** Nạp cấu hình và adapter thật trong context nhỏ; không đọc application.properties của test. */
    private static ApplicationContextRunner runner(ExpireReservationHoldsUseCase useCase) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("car-rental.availability.hold-duration=PT1H",
                        "car-rental.availability.hold-sweep-interval=PT30S",
                        "car-rental.time-zone=Asia/Ho_Chi_Minh", "spring.threads.virtual.enabled=false")
                .withBean(ExpireReservationHoldsUseCase.class, () -> useCase)
                .withUserConfiguration(ReservationHoldExpiryScheduler.class)
                .withInitializer(context -> new ClassPathBeanDefinitionScanner((BeanDefinitionRegistry) context)
                        .scan("com.carrental.availability.config", "com.carrental.shared.config"));
    }
}
