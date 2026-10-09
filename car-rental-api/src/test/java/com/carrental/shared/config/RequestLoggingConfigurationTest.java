package com.carrental.shared.config;

import com.carrental.shared.logging.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Khóa cách đăng ký filter thật, tránh test filter xanh nhưng ứng dụng không gắn filter. */
class RequestLoggingConfigurationTest {

    /** Có đúng một registration bao phủ HTTP, error và async với độ ưu tiên cao nhất. */
    @Test
    void registersExactlyOneEarlyFilterForAllRequestPaths() {
        new ApplicationContextRunner().withUserConfiguration(RequestLoggingConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            var registrations = context.getBeansOfType(FilterRegistrationBean.class);
            assertEquals(1, registrations.size());
            var registration = registrations.get("requestIdFilterRegistration");
            assertNotNull(registration);
            assertInstanceOf(RequestIdFilter.class, registration.getFilter());
            assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
            assertEquals(List.of("/*"), List.copyOf(registration.getUrlPatterns()));
            assertEquals(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC),
                    registration.determineDispatcherTypes());
            assertTrue(registration.isAsyncSupported());
            assertTrue(context.getBeansOfType(RequestIdFilter.class).isEmpty(),
                    "The filter must not also be registered as a separate component.");
        });
    }
}
