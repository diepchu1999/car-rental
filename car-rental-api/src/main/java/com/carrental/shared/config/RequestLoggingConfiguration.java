package com.carrental.shared.config;

import com.carrental.shared.logging.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Lắp filter truy vết HTTP dùng chung; không thêm logic nghiệp vụ hoặc thay response JSON. */
@Configuration(proxyBeanMethods = false)
class RequestLoggingConfiguration {

    /** Đăng ký đúng một lần, chạy trước các filter ứng dụng và hỗ trợ cả error/async dispatch. */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setName("requestIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
