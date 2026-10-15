package com.carrental.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Đăng ký cấu hình Keycloak, không gọi mạng hoặc thay đổi realm khi khởi động backend. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakReaderSettings.class)
class KeycloakReaderConfiguration {
}
