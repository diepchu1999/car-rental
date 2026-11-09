package com.carrental.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.shared.logging.SensitiveSqlLogFilter;
import com.carrental.shared.sql.SensitiveSql;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Security-guideline §3: dùng vòng đời Boot thật, gồm lỗi trước khi singleton bảo vệ SQL được tạo. */
class SensitiveSqlStartupFailureTest {
    /**
     * Context B đóng khiến Boot bỏ dấu đã cấu hình; C reset logging rồi lỗi trong initializer.
     * Context A vẫn sống phải tiếp tục che bind JDBC mà không cần khởi động lại hoặc đăng ký bean mới.
     */
    @Test
    void cachedContextStaysProtectedAfterAnotherApplicationFailsEarly() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try (var cached = application(HealthyConfiguration.class).run()) {
            var probe = cached.getBean(JdbcLogProbe.class);
            assertJdbcProtection(context, probe);
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var transientContext = application(HealthyConfiguration.class).run()) {
                    assertTrue(transientContext.isActive());
                }
                var failed = application(HealthyConfiguration.class);
                var enteredInitializer = new AtomicBoolean();
                failed.addInitializers(app -> {
                    enteredInitializer.set(true);
                    throw new IllegalStateException("Forced early context failure.");
                });
                var failure = assertThrows(IllegalStateException.class, failed::run);
                assertEquals("Forced early context failure.", failure.getMessage());
                assertTrue(enteredInitializer.get());
                assertTrue(cached.isActive());
                assertSame(probe, cached.getBean(JdbcLogProbe.class));
                assertJdbcProtection(context, probe);
                assertEquals(1, context.getTurboFilterList().stream().filter(SensitiveSqlLogFilter.class::isInstance).count());
            }
        } finally {
            // Cô lập test khi bản trước sửa đang đỏ; không chữa trạng thái trước assertion.
            SensitiveSqlLoggingConfiguration.register(context);
        }
    }

    /** SQL trong BeanFactoryPostProcessor đã được che dù bean cấu hình thường chưa được tạo. */
    @Test
    void protectsJdbcBeforeSingletonInitialization() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // Dùng close thật để lần chạy tiếp chắc chắn đi qua stop/reset của Boot.
        try (var previous = application(HealthyConfiguration.class).run()) {
            assertTrue(previous.isActive());
        }
        var application = application(EarlyJdbcConfiguration.class);
        var observed = new AtomicBoolean();
        var logs = new ListAppender<ILoggingEvent>();
        application.addListeners(event -> {
            if (event instanceof ApplicationEnvironmentPreparedEvent) {
                var logger = context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
                logger.setLevel(Level.TRACE);
                logs.setContext(context); logs.start(); logger.addAppender(logs);
                observed.set(true);
            }
        });
        try (var started = application.run()) {
            assertTrue(observed.get());
            assertTrue(started.isActive());
            assertEquals(1, logs.list.size(), "Early sensitive JDBC bind must be suppressed.");
            assertEquals("ordinary-query", logs.list.getFirst().getFormattedMessage());
        } finally {
            context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils").detachAppender(logs);
            context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils").setLevel(null);
            logs.stop();
            SensitiveSqlLoggingConfiguration.register(context);
        }
    }

    /** Boot thật dùng logging của ứng dụng; context tối thiểu không tạo web hay datasource. */
    private static SpringApplication application(Class<?> source) {
        var app = new SpringApplication(source);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
        return app;
    }

    /** Giữ appender riêng, kiểm nội dung thật và log thường; không gọi register để che lỗi vòng đời. */
    private static void assertJdbcProtection(LoggerContext context, JdbcLogProbe probe) {
        var logger = context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        var previous = logger.getLevel(); logger.setLevel(Level.TRACE);
        var logs = new ListAppender<ILoggingEvent>(); logs.setContext(context); logs.start(); logger.addAppender(logs);
        try {
            probe.log();
            assertEquals(1, logs.list.size(), "Cached context must keep suppressing sensitive JDBC binds.");
            assertEquals("ordinary-query", logs.list.getFirst().getFormattedMessage());
        } finally { logger.setLevel(previous); logger.detachAppender(logs); logs.stop(); }
    }

    /** Context giả tối thiểu, vẫn tạo bean đăng ký bảo vệ như ứng dụng hiện tại. */
    @Configuration(proxyBeanMethods = false)
    @Import(SensitiveSqlLoggingConfiguration.class)
    static class HealthyConfiguration {
        /** Giữ một caller sống qua nhiều lần Boot khác khởi động/thất bại. */
        @Bean JdbcLogProbe jdbcLogProbe() { return new JdbcLogProbe(); }
    }

    /** Ghi bind giả ở pha trước mọi singleton thường, không cần PostgreSQL để kiểm vòng đời logging. */
    @Configuration(proxyBeanMethods = false)
    @Import(SensitiveSqlLoggingConfiguration.class)
    static class EarlyJdbcConfiguration {
        /** Static để Spring không phải khởi tạo configuration hoặc filter bean trước pha kiểm. */
        @Bean static BeanFactoryPostProcessor earlyJdbcProbe() {
            return factory -> new JdbcLogProbe().log();
        }
    }

    /** Chỉ phát sinh dữ liệu giả qua đúng logger Spring JDBC và phạm vi SensitiveSql. */
    static class JdbcLogProbe {
        /** Một sự kiện nhạy cảm phải bị chặn, một sự kiện thường phải còn. */
        void log() {
            var logger = LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
            SensitiveSql.execute(() -> { logger.trace("bind {}", "local-fake-secret"); return null; });
            logger.trace("ordinary-query");
        }
    }
}
