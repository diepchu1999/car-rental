package com.carrental.identity.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-0006, BR-120/803/809/810: danh tính chỉ dành cho HTTP integration test có PostgreSQL thật.
 * Chỉ thay nguồn JWKS bằng server loopback; giữ nguyên decoder, filter, use case và SQL production.
 * Không dùng Keycloak local, password grant, mock người gọi hay cấu hình bỏ qua phân quyền.
 * Mỗi context sở hữu khóa và server riêng; chỉ lớp test import tường minh mới được sử dụng.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HttpIdentityTestConfiguration {
    /** Tạo issuer độc lập, không kéo Clock hoặc datasource lên sớm khi đăng ký thuộc tính động. */
    @Bean(destroyMethod = "close")
    TestJwtIssuer testJwtIssuer() throws Exception {
        return new TestJwtIssuer();
    }

    /** Chỉ trỏ cấu hình issuer tới server của context test; không thay JwtDecoder bằng bean giả. */
    @Bean
    DynamicPropertyRegistrar testJwtProperties(TestJwtIssuer issuer) {
        return registry -> {
            registry.add("car-rental.identity.keycloak.base-url", issuer::baseUrl);
            registry.add("car-rental.identity.keycloak.realm", () -> "http-test");
        };
    }

    /** Khóa riêng chỉ ở bộ nhớ; JWKS chỉ công bố khóa công khai, không ghi token vào log. */
    public static final class TestJwtIssuer implements AutoCloseable {
        private final RSAKey key;
        private final HttpServer server;
        private final String adminSubject = UUID.randomUUID().toString();
        private final String customerSubject = UUID.randomUUID().toString();

        /** Lắng nghe đúng loopback và cổng ngẫu nhiên; không mở server fixture ra mạng LAN. */
        private TestJwtIssuer() throws Exception {
            key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String path = "/realms/http-test/protocol/openid-connect/certs";
            server.createContext(path, exchange -> {
                try (exchange) {
                    if (!"GET".equals(exchange.getRequestMethod())
                            || !path.equals(exchange.getRequestURI().getPath())) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                }
            });
            server.start();
        }

        /** URL thuộc server test, dùng chung cho cấu hình decoder và claim issuer đã ký. */
        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** BR-803/810: ADMIN dùng admin-web, không kiêm CUSTOMER để che lỗi chọn audience/vai. */
        public String adminToken(Clock clock) {
            return token(clock, adminSubject, "+84910000001", "ADMIN", "admin-web");
        }

        /** BR-120: CUSTOMER có bằng chứng điều khoản thử, không mang vai nhân viên hay quyền admin. */
        public String customerToken(Clock clock) {
            return token(clock, customerSubject, "+84910000002", "CUSTOMER", "customer-web");
        }

        /** Ký RS256 theo Clock của ứng dụng test, kể cả test dùng mốc cố định khác đồng hồ máy. */
        private String token(Clock clock, String subject, String phone, String role, String client) {
            var now = clock.instant();
            var claims = new JWTClaimsSet.Builder().issuer(baseUrl() + "/realms/http-test")
                    .subject(subject).audience("car-rental-api").issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plusSeconds(300))).claim("typ", "Bearer")
                    .claim("azp", client).claim("phone_number", phone)
                    .claim("realm_access", Map.of("roles", List.of(role)));
            if ("CUSTOMER".equals(role)) {
                claims.claim("terms_version", "http-test-terms-v1")
                        .claim("consented_at", "2026-01-01T00:00:00Z");
            }
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims.build());
            try {
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (com.nimbusds.jose.JOSEException failure) {
                throw new IllegalStateException("Could not sign the HTTP test token.", failure);
            }
        }

        /** Đóng server khi Spring đóng context, không giữ cổng hoặc khóa dùng chung giữa các lớp test. */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
