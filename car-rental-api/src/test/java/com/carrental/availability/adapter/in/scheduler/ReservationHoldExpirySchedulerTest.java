package com.carrental.availability.adapter.in.scheduler;

import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
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

    /** Kiểm mặc định bật, lấy nhịp 45 giây từ cấu hình và đăng ký đúng một job fixed-delay. */
    @Test
    void registersConfiguredFixedDelayAndInitialDelay() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(java.time.Clock.systemUTC());
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        Instant before = Instant.now();
        runner(useCase).withPropertyValues("car-rental.availability.hold-sweep-interval=PT45S")
                .withBean("taskScheduler", TaskScheduler.class, () -> scheduler)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(ReservationHoldExpiryScheduler.class).size());
                    assertEquals(1, context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks().size());
                    var task = ArgumentCaptor.forClass(Runnable.class);
                    var start = ArgumentCaptor.forClass(Instant.class);
                    verify(scheduler).scheduleWithFixedDelay(task.capture(), start.capture(), eq(Duration.ofSeconds(45)));
                    assertFalse(start.getValue().isBefore(before.plusSeconds(45)));
                    assertFalse(start.getValue().isAfter(Instant.now().plusSeconds(45)));
                    verifyNoInteractions(useCase);
                    task.getValue().run();
                    verify(useCase).expireHolds();
                });
    }

    /** Kiểm công tắc test tắt cả adapter job và hạ tầng kích hoạt @Scheduled. */
    @Test
    void disablesBackgroundSchedulingExplicitly() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        runner(useCase).withPropertyValues("car-rental.availability.hold-sweep-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ReservationHoldExpiryScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    verifyNoInteractions(useCase);
                });
    }

    /** Kiểm timer thật vẫn gọi lượt sau khi lượt đầu ném lỗi; không sleep hoặc gọi tay sweep. */
    @Test
    void realSchedulerContinuesAfterFailedInvocation() {
        var useCase = mock(ExpireReservationHoldsUseCase.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch nextSucceeded = new CountDownLatch(1);
        when(useCase.expireHolds()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Expected first sweep failure.");
            }
            nextSucceeded.countDown();
            return 0;
        });
        runner(useCase).withPropertyValues("car-rental.availability.hold-sweep-interval=PT0.05S")
                .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(nextSucceeded.await(5, TimeUnit.SECONDS), "The next scheduled sweep must run.");
                    assertTrue(calls.get() >= 2);
                });
    }

    /** Nạp cấu hình và adapter thật trong context nhỏ; không đọc application.properties của test. */
    private static ApplicationContextRunner runner(ExpireReservationHoldsUseCase useCase) {
        return new ApplicationContextRunner()
                .withPropertyValues("car-rental.availability.hold-duration=PT1H",
                        "car-rental.availability.hold-sweep-interval=PT30S")
                .withBean(ExpireReservationHoldsUseCase.class, () -> useCase)
                .withUserConfiguration(ReservationHoldExpiryScheduler.class)
                .withInitializer(context -> new ClassPathBeanDefinitionScanner((BeanDefinitionRegistry) context)
                        .scan("com.carrental.availability.config"));
    }
}
