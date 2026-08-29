package com.carrental;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra ứng dụng khởi động với PostgreSQL thật và cấu hình test dùng chung.
 *
 * <p>Xác nhận datasource sử dụng database ứng dụng bằng tài khoản
 * không phải superuser, các extension bắt buộc đã có và V001 đã chạy.
 *
 * <p>Ứng dụng sử dụng cổng HTTP ngẫu nhiên để không xung đột
 * với backend đang chạy local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class CarRentalApplicationIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    /**
     * Xác nhận ứng dụng, database và migration bảo vệ extension
     * hoạt động đúng sau khi khởi tạo môi trường test.
     *
     * <p>Các truy vấn được thực hiện qua datasource của ứng dụng,
     * không dùng kết nối quản trị của container.
     */
    @Test
    void startsApplicationWithBootstrappedDatabaseAndAppliedMigration() {
        assertThat(applicationContext).isNotNull();
        assertThat(postgresContainer.isRunning()).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        )).isEqualTo("car_rental");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_user",
                String.class
        )).isEqualTo("car_rental_app");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                Boolean.class
        )).isFalse();

        List<String> extensions = jdbcTemplate.queryForList(
                """
                SELECT extname
                FROM pg_extension
                WHERE extname IN ('btree_gist', 'pgcrypto', 'postgis')
                ORDER BY extname
                """,
                String.class
        );

        assertThat(extensions).containsExactly(
                "btree_gist",
                "pgcrypto",
                "postgis"
        );

        String migrationUser = jdbcTemplate.queryForObject(
                """
                SELECT installed_by
                FROM public.flyway_schema_history
                WHERE script = 'V001__verify_required_extensions.sql'
                  AND success
                """,
                String.class
        );

        assertThat(migrationUser).isEqualTo("car_rental_app");
    }
}