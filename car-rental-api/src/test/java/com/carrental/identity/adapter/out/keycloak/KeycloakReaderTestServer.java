package com.carrental.identity.adapter.out.keycloak;

import com.carrental.identity.config.KeycloakReaderSettings;
import com.carrental.identity.domain.AccountRole;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Server thật cho hợp đồng đọc vai, không phải fixture đăng nhập OTP.
 * Chép nguyên cấu hình client/scope/quyền đọc từ realm repo, chỉ thay secret và thêm user thử.
 * Không nạp flow OTP/theme nên backend test không phụ thuộc JAR đã build của module provider.
 */
final class KeycloakReaderTestServer implements AutoCloseable {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Network network = Network.newNetwork();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String secret = UUID.randomUUID().toString();
    private GenericContainer<?> postgres;
    private GenericContainer<?> keycloak;

    /** Khởi tạo tài nguyên test, chưa gọi Docker hoặc mạng ở constructor. */
    KeycloakReaderTestServer() { }

    /** Cả DB và Keycloak đều trong network tạm, image/platform đọc từ .env.example, không dùng H2. */
    void start() throws IOException {
        Path root = repositoryRoot();
        String password = UUID.randomUUID().toString();
        byte[] realm = json.writeValueAsBytes(readerRealm(root));
        postgres = new GenericContainer<>(DockerImageName.parse(setting(root, "POSTGRES_IMAGE")))
                .withCreateContainerCmdModifier(command -> command.withPlatform(setting(root, "POSTGRES_PLATFORM")))
                .withNetwork(network).withNetworkAliases("reader-postgres")
                .withEnv("POSTGRES_DB", "keycloak").withEnv("POSTGRES_USER", "keycloak")
                .withEnv("POSTGRES_PASSWORD", password)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                .withStartupTimeout(Duration.ofMinutes(3));
        postgres.start();
        keycloak = new GenericContainer<>(DockerImageName.parse(setting(root, "KEYCLOAK_IMAGE")))
                .withNetwork(network).withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://reader-postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak").withEnv("KC_DB_PASSWORD", password)
                .withCopyToContainer(Transferable.of(realm, 0644), "/opt/keycloak/data/import/car-rental-realm.json")
                .withExposedPorts(8080).withCommand("start-dev", "--http-port=8080", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/car-rental/.well-known/openid-configuration").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.start();
    }

    /** Cấu hình adapter trỏ cổng ngẫu nhiên do test sở hữu, không trỏ localhost:8080 của Tech Owner. */
    KeycloakReaderSettings settings() {
        return new KeycloakReaderSettings("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080),
                "car-rental", "identity-reader", secret);
    }

    /** Subject ổn định của fixture, không phải ID/tài khoản local hoặc mã nghiệp vụ trả ra API. */
    static String subject(String fixture) {
        return UUID.nameUUIDFromBytes(("identity-reader-test:" + fixture).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Lấy token client_credentials thật để kiểm riêng quyền ghi bị từ chối; không dùng password grant. */
    String readerToken() throws Exception {
        String form = Map.of("grant_type", "client_credentials", "client_id", "identity-reader", "client_secret", secret)
                .entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(settings().baseUrl()
                        + "/realms/car-rental/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Technical client token request failed.");
        return json.readTree(response.body()).path("access_token").asString();
    }

    /** Chỉ gọi Admin API của container tạm, body/token không được in ra log khi assert thất bại. */
    HttpResponse<String> management(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(settings().baseUrl() + "/admin/realms/car-rental/" + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Lát cắt realm chỉ cho đọc vai: giữ cấu hình quyền thật, không tự cấp thêm quyền quản trị cho reader.
     * Các user và composite bổ sung chỉ phục vụ phép thử hợp lệ/vi phạm BR-809, không sửa realm-export.
     */
    private ObjectNode readerRealm(Path root) throws IOException {
        JsonNode source = json.readTree(Files.readString(root.resolve("infra/keycloak/realm-export.json")));
        assertEquals("car-rental", source.path("realm").asString(), "Reader test must follow the project realm.");
        var realm = json.createObjectNode().put("realm", "car-rental").put("enabled", true)
                .put("sslRequired", "none");
        realm.set("roles", source.required("roles").deepCopy());
        realm.set("clientScopes", source.required("clientScopes").deepCopy());
        realm.putArray("defaultDefaultClientScopes");
        realm.putArray("defaultOptionalClientScopes");
        ObjectNode reader = select(source.required("clients"), "clientId", "identity-reader").deepCopy();
        reader.put("secret", secret);
        realm.putArray("clients").add(reader);
        var noPrivileges = reader.deepCopy().put("clientId", "reader-without-permissions");
        ((ArrayNode) realm.get("clients")).add(noPrivileges);
        var scopeMapping = select(source.required("clientScopeMappings").required("realm-management"),
                "client", "identity-reader").deepCopy();
        realm.putObject("clientScopeMappings").putArray("realm-management").add(scopeMapping);
        ArrayNode users = realm.putArray("users");
        users.add(select(source.required("users"), "serviceAccountClientId", "identity-reader").deepCopy());
        users.addObject().put("username", "service-account-reader-without-permissions").put("enabled", true)
                .put("serviceAccountClientId", "reader-without-permissions");
        for (AccountRole role : AccountRole.values()) {
            addUser(users, "role-" + role.name(), role.name());
        }
        addUser(users, "customer-and-staff", "CUSTOMER", "BRANCH_STAFF");
        addUser(users, "multiple-employees", "ADMIN", "BRANCH_STAFF");
        addUser(users, "without-project-role");
        var composite = ((ArrayNode) realm.path("roles").path("realm")).addObject()
                .put("name", "test-manager-bundle").put("composite", true);
        composite.putObject("composites").putArray("realm").add("BRANCH_MANAGER");
        addUser(users, "inherited-manager", "test-manager-bundle");
        return realm;
    }

    /** Chọn đúng một cấu hình nguồn; thiếu/trùng là lỗi test setup, không fallback sang cấu hình tự nghĩ. */
    private ObjectNode select(JsonNode array, String field, String expected) {
        ObjectNode found = null;
        for (JsonNode item : array) {
            if (item.path(field).isString() && expected.equals(item.path(field).asString())) {
                if (found != null) throw new IllegalStateException("Duplicate reader fixture configuration: " + field);
                found = (ObjectNode) item;
            }
        }
        if (found == null) throw new IllegalStateException("Missing reader fixture configuration: " + field);
        return found;
    }

    /** Không đặt mật khẩu hay mở flow đăng nhập cho user fixture, chỉ cần dữ liệu tra vai theo subject. */
    private void addUser(ArrayNode users, String fixture, String... roles) {
        ArrayNode assigned = users.addObject().put("id", subject(fixture)).put("username", fixture)
                .put("enabled", true).putArray("realmRoles");
        for (String role : roles) assigned.add(role);
    }

    /** Đi ngược cây thư mục để tìm cùng nguồn repo, không đọc .env cá nhân. */
    private Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve(".env.example"))
                    && Files.isRegularFile(path.resolve("infra/keycloak/realm-export.json"))) return path;
        }
        throw new IllegalStateException("Cannot locate repository realm and .env.example.");
    }

    /** Image/platform phải có đúng một giá trị được commit, thiếu hoặc trùng thì không khởi động test. */
    private String setting(Path root, String key) {
        try {
            var values = Files.readAllLines(root.resolve(".env.example")).stream().map(String::strip)
                    .filter(line -> line.startsWith(key + "="))
                    .map(line -> line.substring(key.length() + 1).strip()).toList();
            if (values.size() != 1 || values.getFirst().isBlank()) {
                throw new IllegalStateException("Missing or duplicate " + key + " in .env.example.");
            }
            return values.getFirst();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read .env.example.", failure);
        }
    }

    /** Encode form UTF-8, không nối secret chưa escape vào request. */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Dọn đúng tài nguyên do test tạo, kể cả khi khởi động dở dang; không đụng container/volume local. */
    @Override
    public void close() {
        try {
            if (keycloak != null) keycloak.close();
        } finally {
            try {
                if (postgres != null) postgres.close();
            } finally {
                try { network.close(); } finally { http.close(); }
            }
        }
    }
}
