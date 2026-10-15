package com.carrental.keycloak.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import static org.junit.jupiter.api.Assertions.*;

/** Trình duyệt HTTP cho test: form/cookie/PKCE thật, tuyệt đối không gửi OTP hoặc token tới callback ngoài. */
public final class KeycloakLoginBrowser implements AutoCloseable {
    private static final URI CALLBACK = URI.create("https://oauth.pstmn.io/v1/callback");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI base;
    private final HttpClient http;
    private HttpResponse<String> page;
    private URI callback;
    private String client;
    private String verifier;
    private String state;

    /** Mỗi instance có cookie jar riêng, tương đương một trình duyệt hoặc một người dùng độc lập. */
    public KeycloakLoginBrowser(URI base) {
        this.base = base;
        http = HttpClient.newBuilder().cookieHandler(new LoopbackBrowserCookieManager(base))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Khởi động Authorization Code + S256 bằng cùng redirect đã khai báo cho Postman. */
    public void start(String clientId) throws Exception { start(clientId, Map.of()); }

    /** Tham số bổ sung chỉ ở test giao thức, ví dụ kc_action hoặc thử thiếu PKCE; không sửa server. */
    public void start(String clientId, Map<String, String> extra) throws Exception {
        client = clientId;
        verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        state = UUID.randomUUID().toString();
        callback = null;
        var fields = new LinkedHashMap<String, String>();
        fields.put("client_id", client); fields.put("response_type", "code");
        fields.put("scope", "openid"); fields.put("redirect_uri", CALLBACK.toString()); fields.put("state", state);
        fields.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        fields.put("code_challenge_method", "S256");
        fields.putAll(extra);
        navigate(http.send(request(base.resolve("/realms/car-rental/protocol/openid-connect/auth?" + form(fields)))
                .GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    /** Gửi tới action URL do Keycloak cấp, giữ hidden input và không tự đánh dấu checkbox đồng ý. */
    public void submit(Map<String, String> fields) throws Exception {
        assertNull(callback, "Cannot submit after leaving the login flow.");
        var element = document().selectFirst("form[method=post]");
        assertNotNull(element, "Expected a Keycloak POST form, HTTP " + status());
        URI target = URI.create(element.absUrl("action"));
        requireLocal(target);
        var values = new LinkedHashMap<String, String>();
        for (var input : element.select("input[type=hidden][name]")) values.put(input.attr("name"), input.val());
        values.putAll(fields);
        navigate(http.send(request(target).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build(), HttpResponse.BodyHandlers.ofString()));
    }

    /** Theo redirect nội bộ hữu hạn; callback Postman chỉ được giữ trong bộ nhớ, không gọi mạng ra ngoài. */
    private void navigate(HttpResponse<String> response) throws Exception {
        for (int redirect = 0; redirect < 12; redirect++) {
            page = response;
            assertTrue(response.statusCode() < 500, "Keycloak returned HTTP " + response.statusCode());
            if (response.statusCode() < 300 || response.statusCode() >= 400) return;
            URI target = response.uri().resolve(response.headers().firstValue("location").orElseThrow());
            if (sameCallback(target)) {
                callback = target;
                assertEquals(state, query(target).get("state"), "OIDC state must match this browser flow.");
                return;
            }
            requireLocal(target);
            response = http.send(request(target).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        throw new AssertionError("Too many Keycloak login redirects.");
    }

    /** Chỉ chấp nhận origin container do test sở hữu; không theo URL lạ trong form hoặc redirect. */
    private void requireLocal(URI target) {
        assertEquals(base.getScheme(), target.getScheme(), "Unexpected login origin scheme.");
        assertEquals(base.getAuthority(), target.getAuthority(), "Unexpected login origin authority.");
    }

    /** So nguyên origin/path, không dùng phép startsWith dễ chấp nhận hostname hoặc path giả. */
    private boolean sameCallback(URI uri) {
        return CALLBACK.getScheme().equals(uri.getScheme()) && CALLBACK.getAuthority().equals(uri.getAuthority())
                && CALLBACK.getPath().equals(uri.getPath());
    }

    /** HTML không được ghi vào log vì có action URL và thông tin phiên. */
    public Document document() { return Jsoup.parse(page.body(), page.uri().toString()); }
    /** HTTP gần nhất dùng cho chẩn đoán mà không in nội dung nhạy cảm. */
    public int status() { return page.statusCode(); }
    /** Có code chưa đồng nghĩa có token; bước đổi code vẫn phải đưa đúng PKCE verifier. */
    public boolean hasCode() { return callback != null && query(callback).containsKey("code"); }
    /** Lỗi callback để phân biệt từ chối giao thức với lỗi HTML hoặc lỗi server không liên quan. */
    public String authorizationError() { return callback == null ? null : query(callback).get("error"); }
    /** Kiểm màn hình bằng selector, không phụ thuộc câu chữ bản dịch. */
    public void assertForm(String selector) {
        assertFalse(hasCode(), "The login flow must not issue a code at this step.");
        assertNotNull(document().selectFirst(selector), "Missing login form: " + selector + ", HTTP " + status());
    }
    /** Mã tham chiếu không phải OTP; OTP vẫn lấy từ adapter SMS local thật. */
    public String challengeId() {
        var reference = document().getElementById("sms-challenge-id");
        assertNotNull(reference, "Expected a server-issued SMS challenge reference, HTTP " + status());
        return reference.text();
    }

    /** Đổi code bằng đúng verifier do trình duyệt này giữ, không dùng password grant. */
    public JsonNode exchange() throws Exception {
        var response = exchangeUsing(verifier);
        assertEquals(200, response.statusCode(), "Authorization code exchange failed.");
        return JSON.readTree(response.body());
    }

    /** Dùng verifier sai để chứng minh server bắt buộc PKCE; không có bypass trong provider. */
    public HttpResponse<String> exchangeUsing(String suppliedVerifier) throws Exception {
        assertTrue(hasCode(), "No authorization code was issued.");
        return token(Map.of("grant_type", "authorization_code", "client_id", client,
                "redirect_uri", CALLBACK.toString(), "code", query(callback).get("code"), "code_verifier", suppliedVerifier));
    }

    /** Gọi token endpoint thật; password grant chỉ xuất hiện trong phép thử buộc từ chối. */
    public HttpResponse<String> token(Map<String, String> fields) throws Exception {
        return http.send(request(base.resolve("/realms/car-rental/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Kiểm quyền client kỹ thuật qua Admin REST thật, chỉ gọi container tạm của test. */
    public HttpResponse<String> management(String method, String path, String bearer, String body) throws Exception {
        URI target = base.resolve("/admin/realms/car-rental/" + path);
        requireLocal(target);
        return http.send(request(target).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Chỉ đọc payload token vừa nhận trực tiếp từ container; kiểm chữ ký JWT backend nằm ở phần riêng. */
    public static JsonNode claims(JsonNode tokens) throws Exception {
        String token = tokens.path("access_token").asText();
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "Expected a signed access token.");
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        // Thiếu sub phải đỏ ngay, tránh phép so hai chuỗi rỗng vô tình chứng minh cùng tài khoản.
        JsonNode subject = claims.path("sub");
        assertTrue(subject.isTextual() && !subject.asText().isBlank(),
                "Access token must contain a non-blank textual sub claim.");
        return claims;
    }

    /** Đọc kết quả giao thức mà không đưa token vào message assertion. */
    public static JsonNode json(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }
    /** Timeout hữu hạn để test lỗi không giữ build vô thời hạn. */
    private HttpRequest.Builder request(URI uri) { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)); }
    /** Encode cả dấu cộng đầu số điện thoại, không nối form bằng chuỗi chưa escape. */
    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }
    /** UTF-8 dùng thống nhất cho form và query giao thức OIDC. */
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    /** Tách callback nội bộ trong test; không in code/state lên console. */
    private static Map<String, String> query(URI uri) {
        var values = new LinkedHashMap<String, String>();
        if (uri.getRawQuery() == null) return values;
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }
    /** Đóng client của test, không giữ thread HTTP sau khi container được dọn. */
    @Override public void close() { http.close(); }
}
