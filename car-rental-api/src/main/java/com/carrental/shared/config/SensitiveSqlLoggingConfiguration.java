package com.carrental.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import com.carrental.shared.logging.SensitiveSqlLogFilter;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Che JDBC DEBUG/TRACE ở mọi profile; không tạo datasource proxy hoặc nạp SQL caller formatter. */
@Configuration(proxyBeanMethods = false)
class SensitiveSqlLoggingConfiguration {
    /** Filter thuộc Logback dùng chung, không bị một Spring context đóng gỡ khỏi context khác. */
    @Bean(destroyMethod = "")
    SensitiveSqlLogFilter sensitiveSqlLogFilterRegistration() {
        return register((LoggerContext) LoggerFactory.getILoggerFactory());
    }

    /** Tái dùng đúng một đăng ký cho LoggerContext; stop của Logback sẽ kết thúc đăng ký đó. */
    static SensitiveSqlLogFilter register(LoggerContext context) {
        var lock = context.getConfigurationLock();
        lock.lock();
        try {
            for (var listener : context.getCopyOfListenerList()) {
                if (listener instanceof FilterRegistration registration) {
                    registration.install();
                    return registration.filter;
                }
            }
            var registration = new FilterRegistration(context);
            context.addListener(registration);
            registration.install();
            return registration.filter;
        } finally {
            lock.unlock();
        }
    }

    /** Listener chỉ giữ LoggerContext và filter không trạng thái; không giữ tham chiếu Spring context. */
    private static final class FilterRegistration implements LoggerContextListener {
        private final LoggerContext context;
        private final SensitiveSqlLogFilter filter = new SensitiveSqlLogFilter();

        /** Gắn filter với Logback để nó có cùng vòng đời hệ thống logging. */
        private FilterRegistration(LoggerContext context) {
            this.context = context;
            filter.setContext(context);
        }

        /** Reset đã dừng/xoá turbo filter: phục hồi cùng một bản, không nhân đôi. */
        private void install() {
            filter.start();
            if (!context.getTurboFilterList().contains(filter)) context.addTurboFilter(filter);
        }

        /** Giữ listener khi reset; Logback stop vẫn xoá listener theo vòng đời của chính nó. */
        @Override public boolean isResetResistant() { return true; }

        /** Khởi động logging giữ filter đang được quản lý. */
        @Override public void onStart(LoggerContext ignored) { install(); }

        /** Cấu hình lại không được mất bảo vệ JDBC của context đang sống. */
        @Override public void onReset(LoggerContext ignored) { install(); }

        /** Logback stop giải phóng filter; Spring context mới đăng ký lại sau stop/reset của Boot. */
        @Override public void onStop(LoggerContext ignored) {
            context.getTurboFilterList().remove(filter);
            filter.stop();
        }

        /** Thay mức log không làm thay phạm vi SensitiveSql. */
        @Override public void onLevelChange(Logger logger, Level level) { }
    }
}
