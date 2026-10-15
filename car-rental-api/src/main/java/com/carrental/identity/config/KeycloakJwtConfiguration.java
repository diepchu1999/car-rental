package com.carrental.identity.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * ADR-0006, security-guideline §1: xác minh JWT bằng khóa công khai của realm đã cấu hình.
 * Không lấy issuer/JWKS URL từ token; cấu hình decoder không tự đăng ký bộ lọc HTTP hay cấp quyền.
 */
@Configuration(proxyBeanMethods = false)
class KeycloakJwtConfiguration {
    /** HTTP chỉ tải khóa công khai, có timeout kết nối và không theo redirect sang nguồn khóa khác. */
    @Bean(destroyMethod = "close")
    HttpClient keycloakJwkHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Chỉ tin RS256 của realm; lazy tải JWKS nên startup không phụ thuộc Keycloak sẵn sàng.
     * Nimbus dùng RestOperations cho tải JWKS; RestTemplate ở đây không gửi token hoặc client secret.
     * Giữ dung sai đồng hồ mặc định 60 giây của Spring; bắt buộc exp, kiểm nbf nếu có.
     * Claim typ=Bearer phân biệt access token của Keycloak với ID/refresh token, không phải JOSE header typ.
     */
    @Bean
    JwtDecoder keycloakJwtDecoder(KeycloakReaderSettings settings, Clock clock,
                                  @Qualifier("keycloakJwkHttpClient") HttpClient http,
                                  @Value("${car-rental.identity.jwt.audience}") String audience) {
        if (audience == null || audience.isBlank() || !audience.equals(audience.strip())
                || audience.contains("${") || audience.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid car-rental.identity.jwt.audience.");
        }
        String issuer = settings.baseUrl() + "/realms/" + settings.realm();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs")
                .restOperations(new RestTemplate(factory)).jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(true).build();
        var timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(audience)),
                new JwtClaimValidator<Object>("sub", value -> value instanceof String subject && !subject.isBlank()),
                new JwtClaimValidator<Object>("typ", "Bearer"::equals)));
        return decoder;
    }
}
