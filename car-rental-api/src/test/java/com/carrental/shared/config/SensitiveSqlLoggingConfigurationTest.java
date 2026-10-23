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
    /** TRACE bind bị chặn khi nhạy cảm; ngoài phạm vi vẫn giữ mức log và context đóng gỡ filter. */
    @Test
    void suppressesJdbcTraceOnlyInsideSensitiveScopeAndRemovesFilterOnClose() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        int before = context.getTurboFilterList().size();
        Logger logger = context.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(context);
        logs.start();
        logger.addAppender(logs);
        try {
            logger.setLevel(Level.TRACE);
            new ApplicationContextRunner().withUserConfiguration(SensitiveSqlLoggingConfiguration.class).run(app -> {
                assertNull(app.getStartupFailure());
                assertEquals(before + 1, context.getTurboFilterList().size());
                SensitiveSql.execute(() -> { logger.trace("bind {}", "secret-key"); return null; });
                assertTrue(logs.list.isEmpty());
                logger.trace("ordinary-query");
                assertEquals(1, logs.list.size());
                assertEquals("ordinary-query", logs.list.getFirst().getFormattedMessage());
            });
            assertEquals(before, context.getTurboFilterList().size());
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
            logs.stop();
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
