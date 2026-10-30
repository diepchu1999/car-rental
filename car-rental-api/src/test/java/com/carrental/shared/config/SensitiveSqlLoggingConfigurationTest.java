package com.carrental.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.shared.logging.SqlCallerLogger;
import com.carrental.shared.sql.SensitiveSql;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm wiring thật của filter và đường log riêng P6Spy, không chỉ gọi trực tiếp decide. */
class SensitiveSqlLoggingConfigurationTest {
    /** Reset lặp lại vẫn che TRACE, chỉ một filter và log thường giữ nguyên. */
    @Test
    void preservesProtectionAcrossRepeatedLoggingResets() {
        var context = new LoggerContext();
        context.start();
        try {
            var filter = SensitiveSqlLoggingConfiguration.register(context);
            for (int attempt = 0; attempt < 2; attempt++) {
                context.reset();
                assertEquals(1, context.getTurboFilterList().size());
                assertSame(filter, SensitiveSqlLoggingConfiguration.register(context));
                assertTrue(filter.isStarted());
                assertJdbcProtection(context);
            }
        } finally { context.stop(); }
    }

    /** Boot stop/reset xoá listener; context mới đăng ký lại rồi đóng vẫn bảo vệ context cache. */
    @Test
    void closingAnotherContextAfterBootStopAndResetPreservesProtection() {
        var context = new LoggerContext();
        context.start();
        try {
            var original = SensitiveSqlLoggingConfiguration.register(context);
            context.stop();
            assertFalse(original.isStarted());
            assertTrue(context.getTurboFilterList().isEmpty());
            context.reset();
            context.start();
            new ApplicationContextRunner().withBean("sensitiveFilter",
                    com.carrental.shared.logging.SensitiveSqlLogFilter.class,
                    () -> SensitiveSqlLoggingConfiguration.register(context)).run(app -> {
                assertNull(app.getStartupFailure());
                assertNotSame(original, app.getBean("sensitiveFilter"));
                assertJdbcProtection(context);
            });
            // Spring context vừa đóng; Logback dùng chung vẫn sống cho context được cache.
            assertEquals(1, context.getTurboFilterList().size());
            assertJdbcProtection(context);
            context.reset();
            assertJdbcProtection(context);
        } finally { context.stop(); }
    }

    /** Đăng ký hai lần không nhân đôi; Logback stop giải phóng cả filter lẫn listener của nó. */
    @Test
    void registrationIsSharedAndReleasedWhenLoggingStops() {
        var context = new LoggerContext();
        context.start();
        try {
            var first = SensitiveSqlLoggingConfiguration.register(context);
            assertSame(first, SensitiveSqlLoggingConfiguration.register(context));
            assertEquals(1, context.getTurboFilterList().size());
            context.stop();
            assertFalse(first.isStarted());
            assertTrue(context.getTurboFilterList().isEmpty());
            assertTrue(context.getCopyOfListenerList().isEmpty());
            context.reset();
            assertTrue(context.getTurboFilterList().isEmpty());
        } finally { context.stop(); }
    }

    /** Bean thật không gỡ filter khi Spring context đóng; chỉ Logback quản lý vòng đời bảo vệ. */
    @Test
    void springBeanKeepsProtectionAfterContextClose() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        new ApplicationContextRunner().withUserConfiguration(SensitiveSqlLoggingConfiguration.class).run(app -> {
            assertNull(app.getStartupFailure());
            assertSame(SensitiveSqlLoggingConfiguration.register(context),
                    app.getBean("sensitiveSqlLogFilterRegistration"));
            assertJdbcProtection(context);
        });
        assertJdbcProtection(context);
    }

    /** Ghi qua logger thật, xác nhận bind nhạy cảm bị chặn còn sự kiện thường vẫn được ghi. */
    private static void assertJdbcProtection(LoggerContext context) {
        var logger = context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = logger.getLevel();
        logger.setLevel(Level.TRACE);
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(context); logs.start(); logger.addAppender(logs);
        try {
            SensitiveSql.execute(() -> { logger.trace("bind {}", "local-fake-secret"); return null; });
            logger.trace("ordinary-query");
            assertEquals(1, logs.list.size());
            assertEquals("ordinary-query", logs.list.getFirst().getFormattedMessage());
        } finally {
            logger.setLevel(previous); logger.detachAppender(logs); logs.stop();
        }
    }

    /** logText và logException của P6Spy không lọt payload ngay cả khi không đi qua formatMessage. */
    @Test
    void sanitizesP6SpyTextAndExceptionPaths() {
        Logger logger = (Logger) LoggerFactory.getLogger("p6spy");
        Level previous = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(logger.getLoggerContext());
        logs.start();
        logger.addAppender(logs);
        try {
            logger.setLevel(Level.INFO);
            var bridge = new SqlCallerLogger();
            SensitiveSql.execute(() -> {
                bridge.logText("secret-text");
                bridge.logException(new IllegalArgumentException("secret-cause"));
                return null;
            });
            assertEquals(2, logs.list.size());
            for (var event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("secret-"));
                if (event.getThrowableProxy() != null) {
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains("secret-"));
                }
            }
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
