package com.carrental.identity.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm decoder production bằng chữ ký RSA thật và JWKS qua HTTP loopback, không mock JwtDecoder.
 * Khóa riêng sinh trong bộ nhớ mỗi lượt chạy, không dùng secret hay Keycloak local và không cần Docker.
 */
class KeycloakJwtConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static RSAKey signingKey;
    private static RSAKey otherKey;
    private final KeycloakJwtConfiguration configuration = new KeycloakJwtConfiguration();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger unexpectedRequests = new AtomicInteger();
    private final AtomicReference<String> publishedKeys = new AtomicReference<>();
    private HttpServer server;
    private HttpClient http;
    private KeycloakReaderSettings settings;
    private JwtDecoder decoder;
    private String issuer;

    /** Hai cặp khóa độc lập để chứng minh cùng kid nhưng sai khóa vẫn bị chặn và kiểm xoay khóa. */
    @BeforeAll
    static void generateKeys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("signing-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("rotated-key").generate();
    }

    /** Server chỉ công bố khóa PUBLIC ở endpoint cố định, mọi URL khác đều bị ghi nhận rồi trả 404. */
    @BeforeEach
    void setUp() throws IOException {
        publishedKeys.set(new JWKSet(signingKey.toPublicJWK()).toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/car-rental-test/protocol/openid-connect/certs", exchange -> {
            jwksRequests.incrementAndGet();
            byte[] body = publishedKeys.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        server.createContext("/", exchange -> {
            unexpectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        settings = new KeycloakReaderSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                "car-rental-test", "unused-reader", "unused-test-secret");
        issuer = settings.baseUrl() + "/realms/" + settings.realm();
        http = configuration.keycloakJwkHttpClient();
        decoder = configuration.keycloakJwtDecoder(settings, CLOCK, http, "car-rental-api");
    }

    /** Đóng HTTP client và server do test sở hữu, không để socket/luồng tồn tại sang test tiếp theo. */
    @AfterEach
    void tearDown() {
        try { if (http != null) http.close(); }
        finally { if (server != null) server.stop(0); }
    }

    /** Xác minh chữ ký trước khi trả claims; audience có thể chứa backend cùng audience khác. */
    @Test
    void verifiesSignedAccessTokenAgainstConfiguredJwks() throws Exception {
        String token = sign(claims().audience(List.of("account", "car-rental-api")).build(), signingKey);
        var jwt = decoder.decode(token);
        assertEquals("user-subject", jwt.getSubject());
        assertEquals(issuer, jwt.getIssuer().toString());
        assertEquals(List.of("account", "car-rental-api"), jwt.getAudience());
        assertTrue(jwksRequests.get() > 0, "Signature verification must fetch the published public key.");
        assertEquals(0, unexpectedRequests.get());
    }

    /** Token ký bởi khóa khác vẫn sai dù cố dùng kid trùng khóa đang được công bố. */
    @Test
    void rejectsIncorrectSignatureWithMatchingKeyId() throws Exception {
        var forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID()).build(), claims().build());
        forged.sign(new RSASSASigner(otherKey));
        assertThrows(JwtException.class, () -> decoder.decode(forged.serialize()));
    }

    /** Sửa payload sau khi ký không thể biến chữ ký hợp lệ thành token của subject khác. */
    @Test
    void rejectsTamperedPayload() throws Exception {
        String original = sign(claims().build(), signingKey);
        String[] parts = original.split("\\.");
        String payload = com.nimbusds.jose.util.Base64URL.encode(
                claims().subject("forged-subject").build().toString()).toString();
        assertThrows(JwtException.class, () -> decoder.decode(parts[0] + "." + payload + "." + parts[2]));
    }

    /** JWT alg=none không được coi là đã xác thực dù các claim đều hợp lệ. */
    @Test
    void rejectsUnsignedToken() {
        assertThrows(JwtException.class, () -> decoder.decode(new PlainJWT(claims().build()).serialize()));
    }

    /** Không cho client chuyển thuật toán xác thực sang HMAC bằng header của token. */
    @Test
    void rejectsHmacAlgorithm() throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                claims().build());
        token.sign(new MACSigner(new byte[32]));
        assertThrows(JwtException.class, () -> decoder.decode(token.serialize()));
    }

    /** Khóa RSA đúng không đồng nghĩa được chọn tùy ý thuật toán ngoài RS256 đã cấu hình. */
    @Test
    void rejectsUnconfiguredRsaAlgorithm() throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID()).build(), claims().build());
        token.sign(new RSASSASigner(signingKey));
        assertThrows(JwtException.class, () -> decoder.decode(token.serialize()));
    }

    /** Cùng khóa ký nhưng sai/thiếu issuer phải bị chặn; không lấy issuer mong đợi từ token. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"https://untrusted.invalid/realms/car-rental-test", "http://localhost/other-realm"})
    void rejectsWrongOrMissingIssuer(String value) throws Exception {
        String token = sign(claims().issuer(value).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** Sai hoặc thiếu audience không được dùng token của ứng dụng khác để gọi backend. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"admin-web", "car-rental-api-other"})
    void rejectsWrongOrMissingAudience(String value) throws Exception {
        String token = sign(claims().audience(value == null ? List.of() : List.of(value)).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** Hết hạn vượt dung sai 60 giây phải bị từ chối, kể cả chữ ký vẫn hợp lệ. */
    @Test
    void rejectsExpiredToken() throws Exception {
        String token = sign(claims().expirationTime(Date.from(NOW.minusSeconds(61))).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** Không được bỏ exp để tạo access token không bao giờ hết hạn. */
    @Test
    void rejectsMissingExpiration() throws Exception {
        String token = sign(claims().expirationTime(null).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** nbf ở tương lai ngoài dung sai phải bị chặn; nbf không có thì không tự tạo thêm yêu cầu. */
    @Test
    void rejectsTokenUsedBeforeItsValidityWindow() throws Exception {
        String token = sign(claims().notBeforeTime(Date.from(NOW.plusSeconds(61))).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** Khoá rõ hành vi dung sai chuẩn của Spring, tránh vô tình nới thành nhiều phút hoặc bỏ kiểm hạn. */
    @Test
    void retainsSpringDefaultClockSkew() throws Exception {
        String recentlyExpired = sign(claims().expirationTime(Date.from(NOW.minusSeconds(30))).build(), signingKey);
        String nearlyValid = sign(claims().notBeforeTime(Date.from(NOW.plusSeconds(30))).build(), signingKey);
        assertEquals("user-subject", decoder.decode(recentlyExpired).getSubject());
        assertEquals("user-subject", decoder.decode(nearlyValid).getSubject());
    }

    /** Thiếu subject không được đi tiếp tới bước tạo tài khoản nghiệp vụ. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsMissingOrBlankSubject(String value) throws Exception {
        String token = sign(claims().subject(value).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** ID/refresh token vẫn không dùng được dù có chữ ký, issuer và audience phù hợp. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ID", "Refresh", "Offline"})
    void rejectsNonAccessTokenType(String value) throws Exception {
        String token = sign(claims().claim("typ", value).build(), signingKey);
        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    /** JOSE header cũng phải là loại JWT được hỗ trợ, không chỉ kiểm typ bên trong payload. */
    @Test
    void rejectsUnsupportedJoseHeaderType() throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("unrelated-token"))
                .keyID(signingKey.getKeyID()).build(), claims().build());
        token.sign(new RSASSASigner(signingKey));
        assertThrows(JwtException.class, () -> decoder.decode(token.serialize()));
    }

    /** Xoay khóa với kid mới được xử lý bằng refresh JWKS, không phải tắt kiểm chữ ký. */
    @Test
    void refreshesJwksForRotatedSigningKey() throws Exception {
        decoder.decode(sign(claims().build(), signingKey));
        int before = jwksRequests.get();
        publishedKeys.set(new JWKSet(otherKey.toPublicJWK()).toString());
        assertEquals("user-subject", decoder.decode(sign(claims().build(), otherKey)).getSubject());
        assertTrue(jwksRequests.get() > before);
    }

    /** Header jku không được đổi nguồn tin cậy sang URL do token tự chỉ định. */
    @Test
    void ignoresAttackerControlledJwkUrl() throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(otherKey.getKeyID()).jwkURL(URI.create(settings.baseUrl() + "/attacker-keys")).build(),
                claims().build());
        token.sign(new RSASSASigner(otherKey));
        assertThrows(JwtException.class, () -> decoder.decode(token.serialize()));
        assertEquals(0, unexpectedRequests.get());
    }

    /** Hỏng định dạng hoặc JWKS không hợp lệ phải thất bại, không có chế độ decode không kiểm chữ ký. */
    @Test
    void rejectsMalformedTokenAndInvalidJwks() throws Exception {
        assertThrows(JwtException.class, () -> decoder.decode("not-a-jwt"));
        publishedKeys.set("{\"keys\":[]}");
        String token = sign(claims().build(), signingKey);
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    /** Bean lấy audience từ YAML thật và không gọi discovery/JWKS lúc khởi động context. */
    @Test
    void wiresDecoderWithoutStartupNetworkCall() {
        new ApplicationContextRunner().withInitializer(context -> {
            try {
                for (var source : new YamlPropertySourceLoader().load("jwt-yaml", new ClassPathResource("application.yml"))) {
                    context.getEnvironment().getPropertySources().addLast(source);
                }
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }).withBean(KeycloakReaderSettings.class, () -> settings).withBean(Clock.class, () -> CLOCK)
                .withUserConfiguration(KeycloakJwtConfiguration.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(JwtDecoder.class));
                    assertEquals(0, jwksRequests.get());
                    assertEquals(0, unexpectedRequests.get());
                    assertEquals("car-rental-api", context.getEnvironment().getProperty("car-rental.identity.jwt.audience"));
                });
    }

    /** Cấu hình audience rỗng hoặc placeholder chưa giải quyết phải chặn startup, không chấp nhận mọi aud. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " car-rental-api", "${MISSING_AUDIENCE}"})
    void rejectsInvalidAudienceConfiguration(String audience) {
        assertThrows(IllegalArgumentException.class,
                () -> configuration.keycloakJwtDecoder(settings, CLOCK, http, audience));
    }

    /** Claims hợp lệ tối thiểu của access token Keycloak; từng test chỉ đổi trường đang kiểm. */
    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(issuer).subject("user-subject").audience("car-rental-api")
                .issueTime(Date.from(NOW.minusSeconds(3600))).expirationTime(Date.from(NOW.plusSeconds(300)))
                .claim("typ", "Bearer");
    }

    /** Ký thật bằng khóa riêng trong bộ nhớ, không mock thư viện hoặc gửi private key lên JWKS. */
    private String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
