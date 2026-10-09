package com.carrental;

import com.github.gavlyukovskiy.boot.jdbc.decorator.DecoratedDataSource;
import com.p6spy.engine.spy.P6DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.util.Assert;
import org.testcontainers.containers.BindMode;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Cấu hình PostgreSQL thật dùng chung cho các integration test.
 *
 * <p>Image và platform được đọc từ .env.example.
 * Script khởi tạo được dùng lại từ hạ tầng local.
 *
 * <p>Container dành riêng cho test, không sử dụng database hoặc volume
 * của môi trường local. Cấu hình này không đọc file .env cá nhân.
 *
 * <p>Spring Boot quản lý vòng đời container cùng application context.
 * Các lớp test phải import cấu hình này một cách tường minh.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    private static final String ENV_EXAMPLE_FILE = ".env.example";

    private static final String INIT_SCRIPT_RELATIVE_PATH =
            "infra/postgres/init/20-create-databases.sh";

    private static final String CONTAINER_INIT_SCRIPT =
            "/docker-entrypoint-initdb.d/20-create-databases.sh";

    private static final String POSTGRES_ADMIN_USER = "postgres";
    private static final String POSTGRES_ADMIN_PASSWORD =
            "test_only_postgres_admin_password";
    private static final String POSTGRES_MAINTENANCE_DB = "postgres";

    private static final String APP_DB = "car_rental";
    private static final String APP_USER = "car_rental_app";
    private static final String APP_PASSWORD =
            "test_only_car_rental_app_password";

    private static final String KEYCLOAK_DB = "keycloak";
    private static final String KEYCLOAK_DB_USER = "keycloak";
    private static final String KEYCLOAK_DB_PASSWORD =
            "test_only_keycloak_database_password";

    /**
     * Khởi tạo cấu hình test để Spring tạo các bean được khai báo.
     */
    public PostgresTestConfiguration() {
    }

    /**
     * Chặn test xanh giả do chỉ bật tên profile nhưng datasource không thực sự đi qua P6Spy.
     * Mọi integration test dùng cấu hình này kiểm đúng mode: mặc định Hikari trực tiếp;
     * chỉ lượt chạy chủ đích với sql-log mới có decorator và P6Spy. Không đổi cấu hình production.
     */
    @Bean
    SmartInitializingSingleton verifySqlLoggingDataSourceMode(DataSource dataSource, Environment environment) {
        return () -> {
            boolean enabled = environment.acceptsProfiles(Profiles.of("sql-log"));
            Assert.state(enabled == environment.getProperty("decorator.datasource.enabled", Boolean.class, false),
                    "Datasource decoration must match the explicit sql-log profile.");
            if (enabled) {
                Assert.state(dataSource instanceof DecoratedDataSource,
                        "sql-log must use an actually decorated datasource.");
                DecoratedDataSource decorated = (DecoratedDataSource) dataSource;
                Assert.state(decorated.getDecoratedDataSource() instanceof P6DataSource,
                        "sql-log must execute through P6Spy, not another decorator.");
                Assert.state(decorated.getRealDataSource() instanceof HikariDataSource,
                        "The original Hikari connection pool must be preserved.");
            } else {
                Assert.state(dataSource instanceof HikariDataSource,
                        "Without sql-log the datasource must be Hikari directly, with no decorator.");
            }
        };
    }

    /**
     * Khai báo container PostgreSQL với cấu hình khởi tạo dùng chung.
     *
     * <p>Tài khoản quản trị chỉ phục vụ khởi tạo database, role và extension.
     * Datasource của ứng dụng được cấu hình riêng bằng tài khoản ứng dụng.
     *
     * <p>Spring Boot chịu trách nhiệm khởi động và dừng container.
     * Phương thức này không tự gọi start hoặc stop.
     *
     * @return container đã được cấu hình cho integration test
     * @throws IllegalStateException nếu không tìm thấy tài nguyên cần thiết
     *                               hoặc cấu hình image, platform không hợp lệ
     */
    @Bean
    PostgreSQLContainer postgresContainer() {
        Path repositoryRoot = locateRepositoryRoot();
        Path envExamplePath = repositoryRoot.resolve(ENV_EXAMPLE_FILE);
        Path initScriptPath = repositoryRoot.resolve(INIT_SCRIPT_RELATIVE_PATH);

        Map<String, String> environment = readEnvironmentFile(envExamplePath);

        String image = requiredEnvironmentValue(
                environment,
                "POSTGRES_IMAGE",
                envExamplePath
        );

        String platform = requiredEnvironmentValue(
                environment,
                "POSTGRES_PLATFORM",
                envExamplePath
        );

        return new PostgreSQLContainer(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres")
        )
                .withCreateContainerCmdModifier(
                        command -> command.withPlatform(platform)
                )
                .withDatabaseName(POSTGRES_MAINTENANCE_DB)
                .withUsername(POSTGRES_ADMIN_USER)
                .withPassword(POSTGRES_ADMIN_PASSWORD)
                .withEnv("POSTGRES_APP_DB", APP_DB)
                .withEnv("POSTGRES_APP_USER", APP_USER)
                .withEnv("POSTGRES_APP_PASSWORD", APP_PASSWORD)
                .withEnv("KEYCLOAK_DB", KEYCLOAK_DB)
                .withEnv("KEYCLOAK_DB_USER", KEYCLOAK_DB_USER)
                .withEnv("KEYCLOAK_DB_PASSWORD", KEYCLOAK_DB_PASSWORD)
                .withFileSystemBind(
                        initScriptPath.toString(),
                        CONTAINER_INIT_SCRIPT,
                        BindMode.READ_ONLY
                );
    }

    /**
     * Đăng ký cấu hình datasource trỏ đến database ứng dụng trong container.
     *
     * <p>Không dùng thông tin kết nối mặc định của container,
     * vì thông tin đó trỏ đến database bảo trì bằng tài khoản quản trị.
     *
     * @param postgresContainer container PostgreSQL do Spring quản lý
     * @return bộ đăng ký URL và thông tin đăng nhập của tài khoản ứng dụng
     */
    @Bean
    DynamicPropertyRegistrar applicationDatabaseProperties(
            PostgreSQLContainer postgresContainer
    ) {
        return registry -> {
            registry.add(
                    "spring.datasource.url",
                    () -> applicationJdbcUrl(postgresContainer)
            );
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
        };
    }

    /**
     * Tạo JDBC URL đến database ứng dụng qua cổng được container ánh xạ.
     *
     * @param postgresContainer container đã được khởi động
     * @return JDBC URL của database ứng dụng dành cho test
     */
    private static String applicationJdbcUrl(
            PostgreSQLContainer postgresContainer
    ) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgresContainer.getHost(),
                postgresContainer.getMappedPort(
                        PostgreSQLContainer.POSTGRESQL_PORT
                ),
                APP_DB
        );
    }

    /**
     * Tìm thư mục gốc repo bằng cách đi ngược từ thư mục làm việc.
     *
     * <p>Thư mục được chọn phải chứa cả .env.example và script khởi tạo.
     *
     * @return đường dẫn tuyệt đối đến thư mục gốc repo
     * @throws IllegalStateException nếu thiếu thư mục làm việc
     *                               hoặc không tìm thấy repo phù hợp
     */
    private static Path locateRepositoryRoot() {
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new IllegalStateException(
                    "System property 'user.dir' is missing or blank; "
                            + "cannot locate the repository root."
            );
        }

        Path current = Path.of(workingDirectory)
                .toAbsolutePath()
                .normalize();

        while (current != null) {
            Path envExample = current.resolve(ENV_EXAMPLE_FILE);
            Path initScript = current.resolve(INIT_SCRIPT_RELATIVE_PATH);

            if (Files.isRegularFile(envExample)
                    && Files.isRegularFile(initScript)) {
                return current;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Cannot find the repository root containing both '"
                        + ENV_EXAMPLE_FILE
                        + "' and '"
                        + INIT_SCRIPT_RELATIVE_PATH
                        + "' while walking upward from: "
                        + workingDirectory
        );
    }

    /**
     * Đọc file cấu hình mẫu theo định dạng KEY=VALUE.
     *
     * <p>Bỏ qua dòng trống và dòng chú thích.
     * Báo lỗi khi gặp dòng sai cấu trúc hoặc khóa bị khai báo trùng.
     *
     * @param path đường dẫn đến file cấu hình mẫu
     * @return bản đồ bất biến chứa các khóa và giá trị đã đọc
     * @throws IllegalStateException nếu không đọc được file
     *                               hoặc nội dung không hợp lệ
     */
    private static Map<String, String> readEnvironmentFile(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read environment example file: " + path,
                    exception
            );
        }

        Map<String, String> values = new LinkedHashMap<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalStateException(
                        "Invalid KEY=VALUE entry at "
                                + path + ":" + (index + 1)
                );
            }

            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();

            if (key.isEmpty()) {
                throw new IllegalStateException(
                        "Blank key at " + path + ":" + (index + 1)
                );
            }

            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        "Duplicate key '" + key + "' at "
                                + path + ":" + (index + 1)
                );
            }
        }

        return Map.copyOf(values);
    }

    /**
     * Lấy một biến bắt buộc từ cấu hình mẫu.
     *
     * <p>Không sử dụng giá trị mặc định khi biến bị thiếu hoặc trắng.
     *
     * @param values các biến đã đọc từ file
     * @param key tên biến bắt buộc
     * @param source đường dẫn nguồn để đưa vào thông báo lỗi
     * @return giá trị không trắng của biến
     * @throws IllegalStateException nếu biến bị thiếu hoặc trắng
     */
    private static String requiredEnvironmentValue(
            Map<String, String> values,
            String key,
            Path source
    ) {
        String value = values.get(key);

        if (value == null) {
            throw new IllegalStateException(
                    "Missing required key '" + key + "' in " + source
            );
        }

        if (value.isBlank()) {
            throw new IllegalStateException(
                    "Required key '" + key + "' is blank in " + source
            );
        }

        return value;
    }
}
