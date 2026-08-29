package com.carrental.branch.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.domain.Branch;
import com.carrental.branch.domain.BranchLocation;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng truy vấn khoảng cách và index cho vị trí chi nhánh.
 *
 * <p>Vị trí phục vụ BR-003; kiểu geography và index GiST
 * tuân theo database-guideline mục 5.
 *
 * <p>Các bán kính trong lớp này chỉ là dữ liệu kiểm thử,
 * không quy định bán kính tìm kiếm của sản phẩm.
 *
 * <p>Mỗi lần chạy test có transaction riêng và được rollback.
 * Test sử dụng PostgreSQL và PostGIS thật qua Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BranchPostgisIntegrationTest {

    private static final String FIND_WITHIN_RADIUS_SQL_PATH =
            "sql/branch/find_branches_within_radius_for_test.sql";

    private static final String CHECK_LOCATION_INDEX_SQL_PATH =
            "sql/branch/check_branch_location_index_for_test.sql";

    private static final double ORIGIN_LATITUDE = 10.762622;

    private static final double ORIGIN_LONGITUDE = 106.660172;

    @Autowired
    private WriteBranchPort writeBranchPort;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private SqlLoader sqlLoader;

    private String findWithinRadiusSql;

    private String checkLocationIndexSql;

    /**
     * Tải hai câu lệnh SQL dành riêng cho kiểm thử PostGIS.
     */
    @BeforeEach
    void loadSql() {
        findWithinRadiusSql = sqlLoader.load(
                FIND_WITHIN_RADIUS_SQL_PATH
        );

        checkLocationIndexSql = sqlLoader.load(
                CHECK_LOCATION_INDEX_SQL_PATH
        );
    }

    /**
     * Kiểm tra truy vấn trả đúng các chi nhánh trong bán kính bằng mét.
     *
     * <p>Ba chi nhánh lần lượt nằm tại tâm, cách tâm khoảng 111 mét
     * và cách tâm khoảng 111 kilômét.
     *
     * <p>Tọa độ bất đối xứng giúp phát hiện việc đảo kinh độ và vĩ độ.
     * Các khoảng cách được chọn cách xa ngưỡng kiểm tra để tránh
     * phụ thuộc vào sai số nhỏ trong phép tính địa lý.
     *
     * @param radiusMeters bán kính truy vấn tính bằng mét
     * @param expectedCodes danh sách mã phải trả về theo thứ tự tăng dần
     */
    @ParameterizedTest
    @MethodSource("radiusCases")
    void findsBranchesWithinRadiusInMeters(
            double radiusMeters,
            List<String> expectedCodes
    ) {
        insertBranch(
                "CN-GEO001",
                ORIGIN_LATITUDE,
                ORIGIN_LONGITUDE
        );

        insertBranch(
                "CN-GEO002",
                10.763622,
                ORIGIN_LONGITUDE
        );

        insertBranch(
                "CN-GEO003",
                11.762622,
                ORIGIN_LONGITUDE
        );

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("latitude", ORIGIN_LATITUDE)
                .addValue("longitude", ORIGIN_LONGITUDE)
                .addValue("radius_meters", radiusMeters);

        List<String> actualCodes = jdbcTemplate.queryForList(
                findWithinRadiusSql,
                parameters,
                String.class
        );

        assertEquals(expectedCodes, actualCodes);
    }

    /**
     * Chứng minh migration tạo index GiST hợp lệ trên cột location.
     *
     * <p>Kiểm tra định nghĩa và trạng thái index trong cơ sở dữ liệu,
     * không khẳng định bộ lập kế hoạch luôn chọn index khi bảng rất nhỏ.
     */
    @Test
    void hasValidGistIndexOnLocation() {
        Boolean hasValidIndex = jdbcTemplate.queryForObject(
                checkLocationIndexSql,
                new MapSqlParameterSource(),
                Boolean.class
        );

        assertEquals(
                Boolean.TRUE,
                hasValidIndex,
                "Expected a valid GiST index on branch.branch.location."
        );
    }

    /**
     * Cung cấp bán kính và kết quả mong đợi cho ba trường hợp kiểm thử.
     *
     * <p>Bán kính bằng không chỉ lấy chi nhánh tại tâm.
     * Bán kính 200 mét lấy thêm chi nhánh gần.
     * Bán kính 200.000 mét lấy đủ cả ba chi nhánh.
     *
     * @return các bộ dữ liệu cho test truy vấn khoảng cách
     */
    private static Stream<Arguments> radiusCases() {
        return Stream.of(
                Arguments.of(
                        0.0,
                        List.of("CN-GEO001")
                ),
                Arguments.of(
                        200.0,
                        List.of("CN-GEO001", "CN-GEO002")
                ),
                Arguments.of(
                        200_000.0,
                        List.of("CN-GEO001", "CN-GEO002", "CN-GEO003")
                )
        );
    }

    /**
     * Tạo một chi nhánh làm dữ liệu kiểm thử bằng cổng ghi thật.
     *
     * <p>Khẳng định việc chèn thành công để lỗi chuẩn bị dữ liệu
     * không bị nhầm thành lỗi truy vấn khoảng cách.
     *
     * @param code mã chi nhánh của dữ liệu kiểm thử
     * @param latitude vĩ độ cần lưu
     * @param longitude kinh độ cần lưu
     */
    private void insertBranch(
            String code,
            double latitude,
            double longitude
    ) {
        Branch branch = new Branch(
                code,
                new BranchLocation(latitude, longitude)
        );

        assertTrue(
                writeBranchPort.insert(branch),
                "Expected branch fixture to be inserted: " + code
        );
    }
}