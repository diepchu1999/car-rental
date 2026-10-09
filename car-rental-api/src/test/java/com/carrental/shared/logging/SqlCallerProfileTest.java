package com.carrental.shared.logging;

import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceDecoratorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Kiểm cấu hình thật không đòi nạp lớp caller hoặc tạo proxy khi thiếu profile sql-log. */
class SqlCallerProfileTest {
    /** Context mặc định vẫn khởi tạo khi classloader chủ động cấm nạp cả appender và formatter caller. */
    @Test
    void defaultConfigurationNeverLoadsCallerOrDecoratesDataSource() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        DataSource original = mock(DataSource.class);
        new ApplicationContextRunner()
                .withClassLoader(new CallerRejectingClassLoader(getClass().getClassLoader()))
                .withConfiguration(AutoConfigurations.of(DataSourceDecoratorAutoConfiguration.class))
                .withBean(DataSource.class, () -> original)
                .withInitializer(context -> sources.forEach(source -> context.getEnvironment()
                        .getPropertySources().addLast(source)))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("false", context.getEnvironment().getProperty("decorator.datasource.enabled"));
                    assertSame(original, context.getBean(DataSource.class));
                    assertFalse(context.containsBean("p6SpyDataSourceDecorator"));
                    assertNull(context.getEnvironment().getProperty("decorator.datasource.p6spy.custom-appender-class"));
                });
    }

    /** Chỉ YAML của profile chọn appender caller; không để log-format SingleLine/CustomLine ghi đè. */
    @Test
    void sqlLogProfileSelectsCallerAppenderExplicitly() throws Exception {
        var sources = new YamlPropertySourceLoader().load("sql-log", new ClassPathResource("application-sql-log.yml"));
        var source = sources.getFirst();
        assertEquals(true, source.getProperty("decorator.datasource.enabled"));
        assertEquals("custom", source.getProperty("decorator.datasource.p6spy.logging"));
        assertEquals("com.carrental.shared.logging.SqlCallerLogger",
                source.getProperty("decorator.datasource.p6spy.custom-appender-class"));
        assertNull(source.getProperty("decorator.datasource.p6spy.log-format"));
    }

    /** Chốt lỗi ngay khi hạ tầng mặc định thử nạp caller, thay vì chỉ kiểm không có dòng log. */
    private static final class CallerRejectingClassLoader extends ClassLoader {
        /** Giữ classpath ứng dụng thật, chỉ chặn đúng hai lớp của tính năng local caller. */
        private CallerRejectingClassLoader(ClassLoader parent) {
            super(parent);
        }

        /** Không dùng class literal của caller để bản thân test không chủ động nạp chúng. */
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.carrental.shared.logging.SqlCallerFormatter")
                    || name.equals("com.carrental.shared.logging.SqlCallerLogger")) {
                throw new AssertionError("Default configuration must not load SQL caller classes: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
