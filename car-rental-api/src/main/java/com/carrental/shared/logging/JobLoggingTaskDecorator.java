package com.carrental.shared.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/** Bao ngữ cảnh quanh từng lượt scheduler, gồm cả bộ xử lý lỗi bên trong callback của Spring. */
public final class JobLoggingTaskDecorator implements TaskDecorator {
    private static final ThreadLocal<Boolean> JOB_EXECUTION = new ThreadLocal<>();

    /** Tạo decorator không giữ ID theo đối tượng job hoặc theo lúc đăng ký lịch. */
    public JobLoggingTaskDecorator() {
    }

    /** Nhận diện phạm vi do decorator sở hữu, không suy từ requestId mà client có thể gửi lên. */
    static boolean isJobExecution() {
        return Boolean.TRUE.equals(JOB_EXECUTION.get());
    }

    /**
     * Sinh job-UUID bên trong run để mỗi lần lặp có ID mới; luôn trả lại MDC của worker.
     * Không bắt lỗi, đổi transaction, tự retry hoặc nuốt exception của callback.
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        return () -> {
            String previousId = MDC.get(RequestIdFilter.MDC_KEY);
            String previousApi = MDC.get(RequestIdFilter.API_MDC_KEY);
            Boolean previousExecution = JOB_EXECUTION.get();
            JOB_EXECUTION.set(true);
            MDC.put(RequestIdFilter.MDC_KEY, "job-" + UUID.randomUUID());
            // Observation handler đặt tên khi method @Scheduled chạy; không kế thừa api của worker.
            MDC.put(RequestIdFilter.API_MDC_KEY, "-");
            try {
                runnable.run();
            } finally {
                if (previousId == null) {
                    MDC.remove(RequestIdFilter.MDC_KEY);
                } else {
                    MDC.put(RequestIdFilter.MDC_KEY, previousId);
                }
                if (previousApi == null) {
                    MDC.remove(RequestIdFilter.API_MDC_KEY);
                } else {
                    MDC.put(RequestIdFilter.API_MDC_KEY, previousApi);
                }
                if (previousExecution == null) {
                    JOB_EXECUTION.remove();
                } else {
                    JOB_EXECUTION.set(previousExecution);
                }
            }
        };
    }
}
