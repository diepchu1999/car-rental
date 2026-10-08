package com.carrental.shared.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/** Bao ngữ cảnh quanh từng lượt scheduler, gồm cả bộ xử lý lỗi bên trong callback của Spring. */
public final class JobLoggingTaskDecorator implements TaskDecorator {

    /** Tạo decorator không giữ ID theo đối tượng job hoặc theo lúc đăng ký lịch. */
    public JobLoggingTaskDecorator() {
    }

    /**
     * Sinh job-UUID bên trong run để mỗi lần lặp có ID mới; luôn trả lại MDC của worker.
     * Không bắt lỗi, đổi transaction, tự retry hoặc nuốt exception của callback.
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        return () -> {
            String previousId = MDC.get(RequestIdFilter.MDC_KEY);
            MDC.put(RequestIdFilter.MDC_KEY, "job-" + UUID.randomUUID());
            try {
                runnable.run();
            } finally {
                if (previousId == null) {
                    MDC.remove(RequestIdFilter.MDC_KEY);
                } else {
                    MDC.put(RequestIdFilter.MDC_KEY, previousId);
                }
            }
        };
    }
}
