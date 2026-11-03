package com.carrental.shared.config;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Security-guideline §3: gắn bảo vệ SQL ngay sau khi Boot cấu hình Logback, trước khi tạo bean.
 * Nạp bằng spring.factories để vẫn bảo vệ context cache nếu ứng dụng mới lỗi trước khi refresh.
 */
public final class SensitiveSqlLoggingListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    /** Boot vừa stop/reset logging; dùng lại cơ chế đăng ký duy nhất, độc lập profile và datasource. */
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        SensitiveSqlLoggingConfiguration.register((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    /** Chạy ngay sau LoggingApplicationListener, không chờ singleton hoặc database khởi động. */
    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }
}
