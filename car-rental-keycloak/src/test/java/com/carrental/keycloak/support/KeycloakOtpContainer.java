package com.carrental.keycloak.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keycloak và PostgreSQL thật, chỉ dùng cổng ngẫu nhiên/volume tạm, không đọc .env hoặc chạm CSDL local. */
public final class KeycloakOtpContainer implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final String password = UUID.randomUUID().toString();
    private final String probeSecret = UUID.randomUUID().toString();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final GenericContainer<?> postgres;
    private GenericContainer<?> keycloak;
    private final boolean importLoginRealm;
    private final boolean installProbe;
    private final Map<String, String> loginSecrets = Map.of(
            "CAR_RENTAL_KEYCLOAK_READER_SECRET", UUID.randomUUID().toString(),
            "CAR_RENTAL_LOCAL_ADMIN_PASSWORD", "Test-" + UUID.randomUUID(),
            "CAR_RENTAL_LOCAL_STAFF_PASSWORD", "Test-" + UUID.randomUUID(),
            "CAR_RENTAL_LOCAL_MANAGER_A_PASSWORD", "Test-" + UUID.randomUUID(),
            "CAR_RENTAL_LOCAL_MANAGER_B_PASSWORD", "Test-" + UUID.randomUUID());

    /** Dùng cùng image/platform với cấu hình được commit của hạ tầng. */
    public KeycloakOtpContainer() {
        this(false);
    }

    /** Test đăng nhập import realm thật và không cài probe; test kho OTP giữ cấu hình cũ. */
    public KeycloakOtpContainer(boolean importLoginRealm) {
        this(importLoginRealm, !importLoginRealm);
    }

    /** Cho test giới hạn thời gian dùng probe riêng; đăng nhập thường vẫn kiểm realm không có probe. */
    public KeycloakOtpContainer(boolean importLoginRealm, boolean installProbe) {
        this.importLoginRealm = importLoginRealm;
        this.installProbe = installProbe;
        postgres = new GenericContainer<>(DockerImageName.parse(TestSettings.repositorySetting("POSTGRES_IMAGE")))
                .withCreateContainerCmdModifier(command -> command.withPlatform(TestSettings.repositorySetting("POSTGRES_PLATFORM")))
                .withNetwork(network).withNetworkAliases("otp-postgres")
                .withEnv("POSTGRES_DB", "keycloak").withEnv("POSTGRES_USER", "keycloak")
                .withEnv("POSTGRES_PASSWORD", password).withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    /** Keycloak tự chạy Liquibase extension bằng connection của nó; test không dựng schema hộ. */
    public void start() {
        postgres.start();
        keycloak = server();
        try { keycloak.start(); }
        catch (RuntimeException failure) {
            System.err.println(keycloak.getLogs());
            close(); throw failure;
        }
    }

    /** Tái tạo server nhưng giữ nguyên PostgreSQL để kiểm migration và hạn mức qua restart. */
    public void restart() {
        keycloak.stop(); keycloak = server(); keycloak.start();
    }

    /** Lỗi khởi động phải đúng do adapter SMS giả, không tính lỗi Docker/kết nối là bằng chứng bảo mật. */
    public void assertNormalStartRejectsLocalSms() {
        // Thu log từ đầu: Testcontainers xoá container sau lỗi start(), getLogs() khi đó đã quá muộn.
        // Callback log chạy ở luồng khác nên dùng bộ đệm đồng bộ thay cho StringBuilder.
        var capturedLogs = new StringBuffer();
        try (var normal = server().withCommand("start", "--http-enabled=true", "--hostname-strict=false")
                .withLogConsumer(frame -> capturedLogs.append(frame.getUtf8String()))
                .withStartupAttempts(1).withStartupTimeout(Duration.ofSeconds(90))) {
            var failure = assertThrows(RuntimeException.class, normal::start,
                    () -> "Normal Keycloak startup must reject local SMS. Captured output:\n" + capturedLogs);
            String output = capturedLogs.toString();
            assertTrue(output.contains("Local SMS requires Keycloak start-dev."),
                    () -> "Startup failed without the required local SMS rejection. Failure: " + failure
                            + "\nCaptured container output:\n" + output);
        }
    }

    /** JAR thử được mount riêng, không đưa vào JAR provider thật. */
    private GenericContainer<?> server() {
        var container = new GenericContainer<>(DockerImageName.parse(TestSettings.repositorySetting("KEYCLOAK_IMAGE")))
                .withNetwork(network).withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://otp-postgres:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak").withEnv("KC_DB_PASSWORD", password)
                .withEnv(TestSettings.otp()).withEnv("OTP_TEST_PROBE_SECRET", probeSecret)
                .withEnv("CAR_RENTAL_TERMS_VERSION", "local-test-v1")
                .withCopyFileToContainer(jar("provider.jar"), "/opt/keycloak/providers/car-rental-otp.jar")
                .withCopyFileToContainer(jar("local.sms.jar"), "/opt/keycloak/providers/car-rental-local-sms.jar")
                .withExposedPorts(8080).withCommand("start-dev", "--http-port=8080")
                .waitingFor(Wait.forHttp("/realms/" + (importLoginRealm ? "car-rental" : "master")
                        + "/.well-known/openid-configuration").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        if (importLoginRealm) {
            container.withEnv(loginSecrets).withCommand("start-dev", "--http-port=8080", "--import-realm")
                    .withCopyFileToContainer(MountableFile.forHostPath(
                            TestSettings.repositoryFile("infra/keycloak/realm-export.json"), 0644),
                            "/opt/keycloak/data/import/car-rental-realm.json");
        }
        if (installProbe) {
            container.withCopyFileToContainer(jar("probe.jar"), "/opt/keycloak/providers/car-rental-test-probe.jar");
        }
        return container;
    }

    /** Địa chỉ cổng ngẫu nhiên, không trỏ Keycloak local của Tech Owner. */
    public URI baseUri() { return URI.create("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)); }

    /** Secret giả sinh riêng mỗi container, chỉ trả cho code test giao thức OIDC. */
    public String loginSecret(String name) {
        if (!loginSecrets.containsKey(name)) throw new IllegalArgumentException("Unknown login test secret.");
        return loginSecrets.get(name);
    }

    /** Thiếu bước package phải báo rõ thay vì âm thầm mount nhầm file. */
    private MountableFile jar(String property) {
        String path = System.getProperty(property);
        if (path == null || !java.nio.file.Files.isRegularFile(Path.of(path)))
            throw new IllegalStateException("Run ./mvnw verify; missing packaged fixture: " + property);
        return MountableFile.forHostPath(path, 0644);
    }

    /** HTTP đồng bộ, timeout hữu hạn; lỗi 500 là test đỏ chứ không chuyển thành kết quả nghiệp vụ. */
    public String call(String operation, Map<String, String> fields) throws Exception {
        return callInRealm("master", operation, fields);
    }

    /** Chỉ dùng với probe của container test; chọn realm để điều khiển đồng hồ bản ghi brute force. */
    public String callInRealm(String realm, String operation, Map<String, String> fields) throws Exception {
        String form = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create("http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080)
                        + "/realms/" + encode(realm) + "/otp-probe/" + operation))
                .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Test-Probe", probeSecret).POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    /** Chỉ đọc OTP từ adapter local thật, không thêm đường trả OTP trong production provider. */
    public String code(String id) throws InterruptedException {
        var pattern = java.util.regex.Pattern.compile("LOCAL TEST SMS ONLY challenge="
                + java.util.regex.Pattern.quote(id) + " otp=([0-9]{6})");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            var matcher = pattern.matcher(keycloak.getLogs());
            if (matcher.find()) return matcher.group(1);
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Local SMS log is missing for test challenge.");
    }

    /** Connection độc lập chỉ ở test để quan sát trạng thái sau commit và tạo dữ liệu cửa sổ cũ. */
    public Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/keycloak", "keycloak", password);
    }

    /** Encode form, nhất là dấu cộng đầu số E.164. */
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** Dọn container do test sở hữu; không có tên/container/volume local trong helper. */
    @Override public void close() {
        if (keycloak != null) keycloak.close();
        postgres.close(); network.close(); http.close();
    }
}
