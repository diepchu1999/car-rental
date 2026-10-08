package com.carrental.branch.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kiểm chứng PostgreSQL thật từ chối dữ liệu chi nhánh không hợp lệ.
 *
 * <p>Vị trí phục vụ BR-003. Mã nghiệp vụ và tính duy nhất
 * tuân theo database-guideline mục 2.
 *
 * <p>Test ghi trực tiếp bằng JDBC để không bị validation của domain
 * chặn trước khi dữ liệu đến cơ sở dữ liệu.
 *
 * <p>Mỗi lần chạy test có transaction riêng và được rollback.
 * Sau câu lệnh gây lỗi, test chỉ kiểm tra exception, không chạy thêm SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BranchConstraintIntegrationTest {

    private static final String INSERT_SQL_PATH =
            "sql/branch/insert_branch_for_constraint_test.sql";

    private static final String VALID_LOCATION =
            "SRID=4326;POINT(106.660172 10.762622)";

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private SqlLoader sqlLoader;

    private String insertSql;

    /**
     * Tải câu lệnh ghi trực tiếp dành riêng cho các test ràng buộc.
     */
    @BeforeEach
    void loadSql() {
        insertSql = sqlLoader.load(INSERT_SQL_PATH);
    }

    /**
     * Chứng minh ràng buộc duy nhất trên mã thực sự chặn bản ghi trùng.
     *
     * <p>Lần chèn đầu phải thành công để xác nhận dữ liệu và câu SQL
     * hợp lệ trước khi thử vi phạm ràng buộc.
     */
    @Test
    void rejectsDuplicateCode() {
        String code = "CN-UNIQ01";

        assertEquals(1, insertRawBranch(code, VALID_LOCATION));

        ServerErrorMessage details = assertDatabaseViolation(
                () -> insertRawBranch(code, VALID_LOCATION),
                "23505"
        );

        assertEquals("uq_branch_code", details.getConstraint());
    }

    /**
     * Chứng minh cột mã nghiệp vụ không chấp nhận giá trị null.
     */
    @Test
    void rejectsNullCode() {
        ServerErrorMessage details = assertDatabaseViolation(
                () -> insertRawBranch(null, VALID_LOCATION),
                "23502"
        );

        assertEquals("code", details.getColumn());
    }

    /**
     * Chứng minh CSDL tự kiểm tra định dạng mã nghiệp vụ.
     *
     * @param code mã sai định dạng được gửi thẳng xuống CSDL
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "XE-ABC123",
            "cn-ABC123",
            "CN-abc123",
            "CN-ABC12",
            "CN-ABC1234",
            "CN-ABC12\u00C9",
            "CN-ABC123\n"
    })
    void rejectsInvalidCodeFormat(String code) {
        ServerErrorMessage details = assertDatabaseViolation(
                () -> insertRawBranch(code, VALID_LOCATION),
                "23514"
        );

        assertEquals(
                "chk_branch_code_format",
                details.getConstraint()
        );
    }

    /**
     * Chứng minh cột vị trí không chấp nhận giá trị null.
     */
    @Test
    void rejectsNullLocation() {
        ServerErrorMessage details = assertDatabaseViolation(
                () -> insertRawBranch("CN-NULL01", null),
                "23502"
        );

        assertEquals("location", details.getColumn());
    }

    /**
     * Chứng minh vị trí có đối tượng điểm nhưng không có tọa độ vẫn bị chặn.
     *
     * <p>POINT EMPTY khác SQL NULL nên phải được kiểm tra riêng.
     */
    @Test
    void rejectsEmptyLocation() {
        ServerErrorMessage details = assertDatabaseViolation(
                () -> insertRawBranch(
                        "CN-EMPTY1",
                        "SRID=4326;POINT EMPTY"
                ),
                "23514"
        );

        assertEquals(
                "chk_branch_location_not_empty",
                details.getConstraint()
        );
    }

    /**
     * Ghi dữ liệu đầu vào trực tiếp, không tạo aggregate hoặc gọi write port.
     *
     * <p>Khai báo kiểu tham số tường minh để có thể gửi cả giá trị null.
     *
     * @param code mã cần thử, có thể null hoặc sai định dạng
     * @param location vị trí dạng văn bản có SRID, có thể null hoặc rỗng
     * @return số dòng được chèn khi câu lệnh thành công
     */
    private int insertRawBranch(String code, String location) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("name", "Test Branch", Types.VARCHAR)
                .addValue("address", "123 Test Street", Types.VARCHAR)
                .addValue("location", location, Types.VARCHAR);

        return jdbcTemplate.update(insertSql, parameters);
    }

    /** BR-808: từng cột hiển thị bắt buộc được bảo vệ bởi NOT NULL tại CSDL. */
    @ParameterizedTest
    @ValueSource(strings = {"name", "address"})
    void rejectsNullDisplayField(String field) {
        MapSqlParameterSource parameters = validDisplayParameters().addValue(field, null, Types.VARCHAR);
        ServerErrorMessage details = assertDatabaseViolation(
                () -> jdbcTemplate.update(insertSql, parameters), "23502");
        assertEquals(field, details.getColumn());
    }

    /** BR-808: tên trắng bị đúng CHECK của tên chặn, không phải ràng buộc khác. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankName(String name) {
        MapSqlParameterSource parameters = validDisplayParameters().addValue("name", name);
        ServerErrorMessage details = assertDatabaseViolation(
                () -> jdbcTemplate.update(insertSql, parameters), "23514");
        assertEquals("chk_branch_name_not_blank", details.getConstraint());
    }

    /** BR-808: địa chỉ trắng bị đúng CHECK của địa chỉ chặn. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankAddress(String address) {
        MapSqlParameterSource parameters = validDisplayParameters().addValue("address", address);
        ServerErrorMessage details = assertDatabaseViolation(
                () -> jdbcTemplate.update(insertSql, parameters), "23514");
        assertEquals("chk_branch_address_not_blank", details.getConstraint());
    }

    /** Tạo dữ liệu hợp lệ để mỗi test chỉ thay đúng một trường cần thử phá hoại. */
    private MapSqlParameterSource validDisplayParameters() {
        return new MapSqlParameterSource()
                .addValue("code", "CN-NAME01", Types.VARCHAR)
                .addValue("name", "Central Branch", Types.VARCHAR)
                .addValue("address", "123 Main Street", Types.VARCHAR)
                .addValue("location", VALID_LOCATION, Types.VARCHAR);
    }

    /**
     * Kiểm tra lỗi đến từ PostgreSQL với đúng SQLSTATE, schema và bảng.
     *
     * <p>Trả lại thông tin lỗi có cấu trúc để từng test kiểm tiếp
     * tên constraint hoặc tên cột, không phân tích chuỗi thông báo lỗi.
     *
     * @param action thao tác ghi phải bị PostgreSQL từ chối
     * @param expectedSqlState SQLSTATE mong đợi
     * @return thông tin lỗi có cấu trúc do PostgreSQL cung cấp
     */
    private static ServerErrorMessage assertDatabaseViolation(
            Executable action,
            String expectedSqlState
    ) {
        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                action
        );

        PSQLException postgresFailure = assertInstanceOf(
                PSQLException.class,
                failure.getMostSpecificCause()
        );

        assertEquals(
                expectedSqlState,
                postgresFailure.getSQLState()
        );

        ServerErrorMessage details =
                postgresFailure.getServerErrorMessage();

        assertNotNull(details);
        assertEquals("branch", details.getSchema());
        assertEquals("branch", details.getTable());

        return details;
    }
}
