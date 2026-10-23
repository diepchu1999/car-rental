package com.carrental.shared.config;

import ch.qos.logback.classic.LoggerContext;
import com.carrental.shared.logging.SensitiveSqlLogFilter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Che JDBC DEBUG/TRACE ở mọi profile; không tạo datasource proxy hoặc nạp SQL caller formatter. */
@Configuration(proxyBeanMethods = false)
class SensitiveSqlLoggingConfiguration {
    /** Gắn filter theo vòng đời context; chỉ gỡ đúng filter của context này khi đóng. */
    @Bean
    DisposableBean sensitiveSqlLogFilterRegistration() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var filter = new SensitiveSqlLogFilter();
        filter.setContext(context);
        filter.start();
        context.addTurboFilter(filter);
        return () -> {
            context.getTurboFilterList().remove(filter);
            filter.stop();
        };
    }
}
