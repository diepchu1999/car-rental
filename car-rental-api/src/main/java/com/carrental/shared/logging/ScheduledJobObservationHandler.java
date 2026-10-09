package com.carrental.shared.logging;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.MDC;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Nhận metadata method ngay trước khi Spring gọi job, không phụ thuộc lớp bọc Runnable.
 * Dùng hook Observation đã có của Spring/Actuator, không đọc field riêng hoặc phân tích toString.
 * Chỉ gắn ngữ cảnh trong lượt được JobLoggingTaskDecorator quản lý để không rò MDC ra HTTP/gọi tay.
 */
public final class ScheduledJobObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {
    /** Không giữ trạng thái theo job hoặc thread trong handler dùng chung. */
    public ScheduledJobObservationHandler() {
    }

    /** Chỉ nhận observation của method chạy lịch, không tác động observation HTTP/JDBC khác. */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    /**
     * Context cung cấp user class đã bỏ CGLIB và method gốc dù ngoài nó là OutcomeTrackingRunnable.
     * Nhãn job dùng cùng giới hạn/escape như HTTP trước khi vào MDC.
     * Không xóa api ở onStop: observation dừng trước ErrorHandler nên phải để decorator ngoài cùng
     * khôi phục MDC sau khi tóm tắt lỗi và stack trace được ghi xong.
     */
    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        if (JobLoggingTaskDecorator.isJobExecution()) {
            MDC.put(RequestIdFilter.API_MDC_KEY,
                    LogValueSanitizer.escapeApi(context.getTargetClass().getSimpleName()
                            + "." + context.getMethod().getName()));
        }
    }
}
