package com.carrental.identity.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** ADR-0006, api-guideline §2: resource server JWT stateless, mặc định từ chối đường chưa khai báo. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class IdentitySecurityConfiguration {
    /**
     * Chỉ nhận bearer qua header, không đăng nhập form/Basic hoặc lưu phiên vào cookie.
     * CSRF tắt vì API không dùng credential tự gửi của trình duyệt; không bật CORS wildcard.
     * Error dispatch nội bộ được đi tiếp để giữ lỗi gốc; request trực tiếp vào /error vẫn bị chặn.
     */
    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http,
            @Qualifier("authenticatedCallerFilter") Filter callerFilter,
            @Qualifier("apiSecurityFailureHandler") AuthenticationEntryPoint entryPoint,
            @Qualifier("apiSecurityFailureHandler") AccessDeniedHandler accessDenied,
            @Qualifier("clientNamespaceAuthorization") AuthorizationManager<RequestAuthorizationContext> client,
            @Qualifier("adminNamespaceAuthorization") AuthorizationManager<RequestAuthorizationContext> admin)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .addFilterAfter(callerFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers("/api/v1/client/**").access(client)
                        .requestMatchers("/api/v1/admin/**").access(admin)
                        .anyRequest().denyAll())
                .build();
    }

    /** Filter chỉ chạy sau bearer trong Security, không bị Boot đăng ký thêm ở servlet container. */
    @Bean
    FilterRegistrationBean<Filter> authenticatedCallerFilterRegistration(
            @Qualifier("authenticatedCallerFilter") Filter callerFilter) {
        var registration = new FilterRegistrationBean<>(callerFilter);
        registration.setEnabled(false);
        return registration;
    }
}
