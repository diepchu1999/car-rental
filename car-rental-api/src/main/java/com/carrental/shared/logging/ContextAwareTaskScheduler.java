package com.carrental.shared.logging;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Gắn tên hàm cho ScheduledMethodRunnable được đăng ký trực tiếp trước bộ xử lý lỗi và future.
 * Với @Scheduled, Spring bọc OutcomeTrackingRunnable trước điểm này: ScheduledJobObservationHandler
 * sẽ cập nhật tên từ metadata method khi thực thi, không cố bóc wrapper hoặc đọc field riêng.
 * Boot vẫn cấu hình pool, vòng đời và nhịp chạy trên ThreadPoolTaskScheduler như trước.
 */
public final class ContextAwareTaskScheduler extends ThreadPoolTaskScheduler {
    /** Decorator bao cả bộ xử lý lỗi, chịu trách nhiệm khôi phục MDC sau mỗi lượt chạy. */
    public ContextAwareTaskScheduler() {
        setTaskDecorator(new JobLoggingTaskDecorator());
    }

    /** Giữ nguyên trigger, chỉ gắn ngữ cảnh cho callback của mỗi lượt. */
    @Override
    public @Nullable ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return super.schedule(withApi(task), trigger);
    }

    /** Giữ nguyên thời điểm thực hiện callback một lần. */
    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return super.schedule(withApi(task), startTime);
    }

    /** Giữ nguyên thời điểm đầu và chu kỳ fixed-rate. */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return super.scheduleAtFixedRate(withApi(task), startTime, period);
    }

    /** Giữ nguyên chu kỳ fixed-rate với lượt đầu do Spring quyết định. */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return super.scheduleAtFixedRate(withApi(task), period);
    }

    /** Giữ nguyên độ trễ đầu và fixed-delay của job BR-103. */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return super.scheduleWithFixedDelay(withApi(task), startTime, delay);
    }

    /** Giữ nguyên khoảng đợi giữa hai lượt fixed-delay. */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return super.scheduleWithFixedDelay(withApi(task), delay);
    }

    /**
     * Lấy tên từ method thật, không đoán từ exception hoặc Runnable.toString().
     * Không dọn api tại đây: ErrorHandler phía ngoài cần đọc nó khi callback ném lỗi.
     * JobLoggingTaskDecorator bao future sẽ dọn cả api/requestId sau ErrorHandler.
     * Callback chưa có metadata tại điểm đăng ký dùng dấu gạch; hook observation có thể cập nhật
     * khi method thực thi. Runnable thông thường giữ dấu gạch, không dùng nhầm tên job trước.
     */
    private Runnable withApi(Runnable task) {
        String api = task instanceof ScheduledMethodRunnable scheduled
                ? ClassUtils.getUserClass(scheduled.getTarget()).getSimpleName() + "." + scheduled.getMethod().getName()
                : "-";
        return () -> {
            MDC.put(RequestIdFilter.API_MDC_KEY, api);
            task.run();
        };
    }
}
