package com.carrental.shared.config;

import com.carrental.shared.logging.ContextAwareTaskScheduler;
import com.carrental.shared.logging.FailureSummary;
import com.carrental.shared.logging.JobLoggingTaskDecorator;
import com.carrental.shared.logging.ScheduledJobObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ScheduledExecutorService;

/** Bổ sung log cho scheduler pool hiện tại, giữ cấu hình lịch, số thread và vòng đời do Boot quản lý. */
@Configuration(proxyBeanMethods = false)
class SchedulerLoggingConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerLoggingConfiguration.class);

    /** Dùng builder của Boot để giữ pool/shutdown/customizer; không ghi đè scheduler do bên khác cung cấp. */
    @Bean
    @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})
    ContextAwareTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.configure(new ContextAwareTaskScheduler());
    }

    /** Boot gắn handler vào registry mà ScheduledTasksObservationAutoConfiguration cấp cho registrar. */
    @Bean
    ScheduledJobObservationHandler scheduledJobObservationHandler() {
        return new ScheduledJobObservationHandler();
    }

    /**
     * Thay ErrorHandler mặc định để không ghi trùng lỗi; lỗi đã ra khỏi proxy transaction trước đó.
     * Giữ hành vi job định kỳ của Spring: ghi lỗi rồi cho phép lượt tiếp theo, không retry ngay.
     * Decorator bao cả ErrorHandler nên dòng tóm tắt và các log của job dùng cùng requestId.
     */
    @Bean
    ThreadPoolTaskSchedulerCustomizer schedulerLoggingCustomizer() {
        return scheduler -> {
            scheduler.setTaskDecorator(new JobLoggingTaskDecorator());
            scheduler.setErrorHandler(failure -> LOGGER.error(FailureSummary.format(failure, "-", "-"), failure));
        };
    }
}
