package com.carrental.identity.adapter.out.keycloak;

import com.carrental.identity.application.port.out.ReadAccountRolesPort;
import com.carrental.identity.config.KeycloakReaderSettings;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;

/**
 * BR-801/809, ADR-0006: chỉ đọc vai hiệu lực từ realm qua client kỹ thuật, không gán vai hoặc tạo user.
 * Không cache vai để luồng gán chi nhánh không dựa trên snapshot cũ. Không ghi token, secret, subject
 * hoặc body của Keycloak vào exception/log (security-guideline §3).
 */
@Component
class KeycloakAccountRolesReadAdapter implements ReadAccountRolesPort {
    private final KeycloakReaderSettings settings;
    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Dùng HTTP client riêng, không chuyển tiếp credential theo redirect và không chờ mạng vô hạn. */
    @Autowired
    KeycloakAccountRolesReadAdapter(KeycloakReaderSettings settings) {
        this(settings, createClient(settings));
    }

    /** Nhận HTTP transport để test hợp đồng request/response mà không cần Keycloak local. */
    KeycloakAccountRolesReadAdapter(KeycloakReaderSettings settings, RestClient client) {
        this.settings = settings;
        this.client = client;
    }

    /**
     * Lấy token service account riêng cho lượt đọc, kiểm user trước rồi đọc realm roles hiệu lực.
     * Chỉ 404 ở bước đọc user trả Optional rỗng; lỗi ở bước token/vai không được giả thành thiếu user.
     */
    @Override
    public Optional<AccountRoles> findBySubject(String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()
                || keycloakSubject.chars().anyMatch(Character::isISOControl)
                || keycloakSubject.equals(".") || keycloakSubject.equals("..")) {
            throw new IllegalArgumentException("Keycloak subject must be a nonblank identifier.");
        }
        String token = accessToken();
        JsonNode user = exchange(client.get()
                .uri("/admin/realms/{realm}/users/{subject}", settings.realm(), keycloakSubject)
                .headers(headers -> headers.setBearerAuth(token)), "user lookup", true);
        if (user == null) {
            return Optional.empty();
        }
        if (!user.isObject() || !keycloakSubject.equals(requiredString(user, "id", "user lookup"))) {
            throw failed("user lookup", "unexpected user representation");
        }
        JsonNode roles = exchange(client.get()
                .uri("/admin/realms/{realm}/users/{subject}/role-mappings/realm/composite",
                        settings.realm(), keycloakSubject)
                .headers(headers -> headers.setBearerAuth(token)), "role lookup", false);
        if (roles == null || !roles.isArray()) {
            throw failed("role lookup", "expected a role array");
        }
        var recognized = EnumSet.noneOf(AccountRole.class);
        for (JsonNode role : roles) {
            String name = requiredString(role, "name", "role lookup");
            if (!role.path("clientRole").isBoolean() || role.path("clientRole").asBoolean()) {
                throw failed("role lookup", "expected realm roles");
            }
            // Vai hệ thống như offline_access/default-roles không cấp quyền nghiệp vụ BR-801.
            for (AccountRole candidate : AccountRole.values()) {
                if (candidate.name().equals(name)) {
                    recognized.add(candidate);
                }
            }
        }
        try {
            return Optional.of(new AccountRoles(recognized));
        } catch (IllegalArgumentException failure) {
            throw failed("role lookup", "multiple employee roles violate BR-809");
        }
    }

    /**
     * Client credentials chỉ dùng cho client kỹ thuật; không gửi mật khẩu hay token của người dùng.
     * Ghi form qua streaming body để tránh RestClient DEBUG gọi toString trên form chứa secret.
     */
    private String accessToken() {
        byte[] form = ("grant_type=client_credentials&client_id="
                + URLEncoder.encode(settings.readerClientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(settings.readerSecret(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        JsonNode result = exchange(client.post().uri("/realms/{realm}/protocol/openid-connect/token", settings.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(output -> output.write(form)),
                "token request", false);
        String token = requiredString(result, "access_token", "token request");
        if (!"Bearer".equalsIgnoreCase(requiredString(result, "token_type", "token request"))
                || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw failed("token request", "invalid bearer token representation");
        }
        return token;
    }

    /**
     * Chỉ chấp nhận 200; không đọc body lỗi. Chủ động bỏ cause HTTP/JSON vì nó có thể chứa body,
     * token hoặc URL. Lỗi nội bộ vẫn có stack trace mới và bước/status an toàn để chẩn đoán.
     */
    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request, String operation, boolean allowNotFound) {
        try {
            return request.accept(MediaType.APPLICATION_JSON).exchange((sent, received) -> {
                int status = received.getStatusCode().value();
                if (allowNotFound && status == 404) {
                    return null;
                }
                if (status != 200) {
                    throw failed(operation, "HTTP " + status);
                }
                JsonNode body = json.readTree(received.getBody());
                if (body == null || body.isNull() || body.isMissingNode()) {
                    throw failed(operation, "empty response");
                }
                return body;
            });
        } catch (KeycloakReadFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failed(operation, "transport or response decoding error");
        }
    }

    /** Thiếu trường hoặc trường không phải chuỗi là lỗi hợp đồng, không tự đổi thành chuỗi rỗng. */
    private String requiredString(JsonNode object, String field, String operation) {
        if (object == null || !object.isObject() || !object.path(field).isString()
                || object.path(field).asString().isBlank()) {
            throw failed(operation, "missing or invalid " + field);
        }
        return object.path(field).asString();
    }

    /** Timeout là giới hạn vận hành của HTTP, không phải chính sách nghiệp vụ. */
    private static RestClient createClient(KeycloakReaderSettings settings) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(settings.baseUrl()).requestFactory(factory).build();
    }

    /** Chỉ nhận các nhãn cố định trong code và mã HTTP, không nhận nội dung từ Keycloak. */
    private static KeycloakReadFailure failed(String operation, String reason) {
        return new KeycloakReadFailure("Keycloak " + operation + " failed: " + reason + ".");
    }

    /** Phân biệt lỗi đã làm sạch với lỗi transport cần bỏ dữ liệu nhạy cảm trong cause. */
    private static final class KeycloakReadFailure extends IllegalStateException {
        /** Giữ stack trace của adapter, không giữ exception chứa dữ liệu từ xa. */
        private KeycloakReadFailure(String message) {
            super(message);
        }
    }
}
