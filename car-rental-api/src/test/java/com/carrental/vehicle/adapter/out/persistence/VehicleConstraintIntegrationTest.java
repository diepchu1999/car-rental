package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Kiểm chứng các ràng buộc chính của bảng xe bằng PostgreSQL thật.
 *
 * <p>BR-001 yêu cầu loại sở hữu bất biến sau khi tạo.
 * BR-003 yêu cầu xe công ty có chi nhánh.
 * Tính duy nhất của mã và biển số tuân theo database-guideline.
 *
 * <p>Test ghi trực tiếp bằng JDBC để kiểm tra khả năng bảo vệ
 * của CSDL, không dựa vào validation của domain.
 *
 * <p>Mỗi test có transaction riêng và được rollback.
 * Sau câu lệnh gây lỗi, test chỉ kiểm tra exception,
 * không chạy tiếp SQL trong transaction đã lỗi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleConstraintIntegrationTest {

    private static final String INSERT_SQL_PATH =
            "sql/vehicle/insert_vehicle_for_constraint_test.sql";

    private static final String UPDATE_OWNERSHIP_SQL_PATH =
            "sql/vehicle/update_vehicle_ownership_for_constraint_test.sql";

    private static final String VEHICLE_CODE = "XE-8KQ4M2";
    private static final String OTHER_VEHICLE_CODE = "XE-7TR9W2";

    private static final String PLATE_NUMBER = "51H-123.45";
    private static final String OTHER_PLATE_NUMBER = "51H-678.90";

    private static final Long BRANCH_ID = 42L;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private SqlLoader sqlLoader;

    private String insertSql;
    private String updateOwnershipSql;

    /**
     * Tải hai câu SQL dành riêng cho test ràng buộc.
     */
    @BeforeEach
    void loadSql() {
        insertSql = sqlLoader.load(INSERT_SQL_PATH);
        updateOwnershipSql = sqlLoader.load(UPDATE_OWNERSHIP_SQL_PATH);
    }

    /** BR-018: CSDL chấp nhận mọi số chỗ được chốt cùng cả hai hộp số. */
    @ParameterizedTest
    @CsvSource({"4, MANUAL", "4, AUTOMATIC", "5, MANUAL", "5, AUTOMATIC",
            "7, MANUAL", "7, AUTOMATIC", "16, MANUAL", "16, AUTOMATIC"})
    void acceptsSupportedSpecifications(int seats, String transmission) {
        MapSqlParameterSource parameters = specificationParameters()
                .addValue("seats", seats, Types.INTEGER)
                .addValue("transmission", transmission, Types.VARCHAR);
        assertEquals(1, jdbcTemplate.update(insertSql, parameters));
    }

    /** Ghi SQL trực tiếp số chỗ sai phải nhận 23514 đúng constraint, không dựa vào domain. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 3, 6, 8, 17})
    void rejectsUnsupportedSeats(int seats) {
        MapSqlParameterSource parameters = specificationParameters().addValue("seats", seats, Types.INTEGER);
        assertDatabaseViolation(() -> jdbcTemplate.update(insertSql, parameters), "23514", "chk_vehicle_seats");
    }

    /** Hộp số ngoài tập BR-018 phải bị constraint riêng từ chối. */
    @ParameterizedTest
    @ValueSource(strings = {"CVT", "manual", "", " "})
    void rejectsUnsupportedTransmission(String transmission) {
        MapSqlParameterSource parameters = specificationParameters()
                .addValue("transmission", transmission, Types.VARCHAR);
        assertDatabaseViolation(() -> jdbcTemplate.update(insertSql, parameters),
                "23514", "chk_vehicle_transmission");
    }

    /** BR-018: cả chuỗi rỗng và khoảng trắng ở hãng đều bị CSDL chặn. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\r\n"})
    void rejectsBlankMake(String make) {
        MapSqlParameterSource parameters = specificationParameters().addValue("make", make, Types.VARCHAR);
        assertDatabaseViolation(() -> jdbcTemplate.update(insertSql, parameters),
                "23514", "chk_vehicle_make_not_blank");
    }

    /** BR-018: kiểm riêng constraint dòng xe để tránh một constraint khác che lỗi. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\r\n"})
    void rejectsBlankModel(String model) {
        MapSqlParameterSource parameters = specificationParameters().addValue("model", model, Types.VARCHAR);
        assertDatabaseViolation(() -> jdbcTemplate.update(insertSql, parameters),
                "23514", "chk_vehicle_model_not_blank");
    }

    /** Từng cột mới đều NOT NULL; kiểm SQLSTATE 23502 và đúng cột gây lỗi. */
    @ParameterizedTest
    @ValueSource(strings = {"seats", "transmission", "make", "model"})
    void rejectsNullSpecificationColumn(String column) {
        int type = column.equals("seats") ? Types.INTEGER : Types.VARCHAR;
        MapSqlParameterSource parameters = specificationParameters().addValue(column, null, type);
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(insertSql, parameters));
        PSQLException postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23502", postgres.getSQLState());
        ServerErrorMessage details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("vehicle", details.getSchema());
        assertEquals("vehicle", details.getTable());
        assertEquals(column, details.getColumn());
    }

    /** Dữ liệu hợp lệ để mỗi test BR-018 chỉ đổi đúng một trường cần kiểm. */
    private static MapSqlParameterSource specificationParameters() {
        return new MapSqlParameterSource()
                .addValue("code", "XE-SPEC01", Types.VARCHAR)
                .addValue("plateNumber", "SPEC-01", Types.VARCHAR)
                .addValue("ownershipType", "COMPANY", Types.VARCHAR)
                .addValue("fuelType", "PETROL", Types.VARCHAR)
                .addValue("branchId", 42L, Types.BIGINT)
                .addValue("status", "DRAFT", Types.VARCHAR)
                .addValue("inspectionExpiresOn", null, Types.DATE)
                .addValue("liabilityInsuranceExpiresOn", null, Types.DATE)
                .addValue("seats", 5, Types.INTEGER)
                .addValue("transmission", "AUTOMATIC", Types.VARCHAR)
                .addValue("make", "Toyota", Types.VARCHAR)
                .addValue("model", "Vios", Types.VARCHAR);
    }

    /**
     * Chứng minh xe công ty có chi nhánh được CSDL chấp nhận.
     *
     * <p>BR-003 không được triển khai thành ràng buộc
     * vô tình chặn mọi xe công ty.
     */
    @Test
    void acceptsCompanyWithBranch() {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                )
        );
    }

    /**
     * Chứng minh yêu cầu có chi nhánh chỉ áp dụng cho xe công ty.
     *
     * <p>Đây là kiểm tra mô hình dữ liệu có hai loại sở hữu theo BR-001,
     * không mở thêm chức năng tạo xe đối tác qua API.
     */
    @Test
    void acceptsPartnerWithoutBranch() {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "PARTNER",
                        null
                )
        );
    }

    /**
     * Chứng minh mã xe không được trùng.
     *
     * <p>Hai bản ghi dùng biển số khác nhau để chỉ vi phạm
     * ràng buộc duy nhất của mã.
     */
    @Test
    void rejectsDuplicateCode() {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                )
        );

        assertDatabaseViolation(
                () -> insertRawVehicle(
                        VEHICLE_CODE,
                        OTHER_PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                ),
                "23505",
                "uq_vehicle_code"
        );
    }

    /**
     * Chứng minh một biển số không thể thuộc hai bản ghi xe.
     *
     * <p>Hai bản ghi dùng mã khác nhau để chỉ vi phạm
     * ràng buộc duy nhất của biển số.
     */
    @Test
    void rejectsDuplicatePlateNumber() {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                )
        );

        assertDatabaseViolation(
                () -> insertRawVehicle(
                        OTHER_VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                ),
                "23505",
                "uq_vehicle_plate_number"
        );
    }

    /**
     * Chứng minh BR-003 được bảo vệ ngay tại CSDL.
     */
    @Test
    void rejectsCompanyWithoutBranch() {
        assertDatabaseViolation(
                () -> insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        null
                ),
                "23514",
                "chk_vehicle_company_has_branch"
        );
    }

    /**
     * Chứng minh BR-001 chặn thay đổi loại sở hữu theo cả hai chiều.
     *
     * <p>Dữ liệu thử luôn có branchId để trạng thái đích COMPANY
     * không đồng thời vi phạm ràng buộc phải có chi nhánh.
     * Test này chỉ tập trung vào trigger bảo vệ loại sở hữu.
     *
     * @param originalOwnershipType loại sở hữu lúc tạo bản ghi
     * @param replacementOwnershipType loại sở hữu khác cần thử cập nhật
     */
    @ParameterizedTest
    @CsvSource({
            "COMPANY, PARTNER",
            "PARTNER, COMPANY"
    })
    void rejectsOwnershipTypeChange(
            String originalOwnershipType,
            String replacementOwnershipType
    ) {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        originalOwnershipType,
                        BRANCH_ID
                )
        );

        ServerErrorMessage details = assertDatabaseViolation(
                () -> updateRawOwnershipType(
                        VEHICLE_CODE,
                        replacementOwnershipType
                ),
                "23514",
                "chk_vehicle_ownership_type_immutable"
        );

        assertEquals("ownership_type", details.getColumn());
    }

    /**
     * Chứng minh trigger cũng nhận ra việc thay loại sở hữu bằng null.
     *
     * <p>Trường hợp này bảo vệ cách so sánh IS DISTINCT FROM:
     * trigger phải báo lỗi bất biến trước khi tới kiểm tra NOT NULL.
     */
    @Test
    void rejectsClearingOwnershipType() {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        "COMPANY",
                        BRANCH_ID
                )
        );

        ServerErrorMessage details = assertDatabaseViolation(
                () -> updateRawOwnershipType(VEHICLE_CODE, null),
                "23514",
                "chk_vehicle_ownership_type_immutable"
        );

        assertEquals("ownership_type", details.getColumn());
    }

    /**
     * Chứng minh trigger không chặn cập nhật giữ nguyên loại sở hữu.
     *
     * @param ownershipType loại sở hữu được gán lại cùng giá trị
     */
    @ParameterizedTest
    @ValueSource(strings = {"COMPANY", "PARTNER"})
    void allowsAssigningSameOwnershipType(String ownershipType) {
        assertEquals(
                1,
                insertRawVehicle(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        ownershipType,
                        BRANCH_ID
                )
        );

        assertEquals(
                1,
                updateRawOwnershipType(VEHICLE_CODE, ownershipType)
        );
    }

    /**
     * Chèn xe trực tiếp để kiểm ràng buộc, không đi qua aggregate.
     *
     * <p>Các trường không phải đối tượng kiểm tra dùng giá trị hợp lệ:
     * nhiên liệu PETROL, trạng thái DRAFT và ngày giấy tờ chưa có.
     *
     * <p>branchId là tham chiếu logic, không có khoá ngoại xuyên module.
     * Sự tồn tại của chi nhánh sẽ được kiểm ở tầng application;
     * bộ test này chỉ kiểm các ràng buộc của bảng vehicle.
     *
     * @param code mã xe cần thử
     * @param plateNumber biển số cần thử
     * @param ownershipType loại sở hữu cần thử
     * @param branchId ID chi nhánh, có thể null
     * @return số dòng được chèn nếu thành công
     */
    private int insertRawVehicle(
            String code,
            String plateNumber,
            String ownershipType,
            Long branchId
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("plateNumber", plateNumber, Types.VARCHAR)
                .addValue("seats", 5, Types.INTEGER)
                .addValue("transmission", "AUTOMATIC", Types.VARCHAR)
                .addValue("make", "Toyota", Types.VARCHAR)
                .addValue("model", "Vios", Types.VARCHAR)
                .addValue("ownershipType", ownershipType, Types.VARCHAR)
                .addValue("fuelType", "PETROL", Types.VARCHAR)
                .addValue("branchId", branchId, Types.BIGINT)
                .addValue("status", "DRAFT", Types.VARCHAR)
                .addValue("inspectionExpiresOn", null, Types.DATE)
                .addValue("liabilityInsuranceExpiresOn", null, Types.DATE);

        return jdbcTemplate.update(insertSql, parameters);
    }

    /**
     * Thử cập nhật loại sở hữu trực tiếp để kích hoạt trigger.
     *
     * @param code mã xe đã được chèn trong test
     * @param ownershipType giá trị muốn cập nhật, có thể null
     * @return số dòng được cập nhật nếu thành công
     */
    private int updateRawOwnershipType(
            String code,
            String ownershipType
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("ownershipType", ownershipType, Types.VARCHAR);

        return jdbcTemplate.update(updateOwnershipSql, parameters);
    }

    /**
     * Kiểm tra lỗi đến từ đúng ràng buộc của bảng xe trong PostgreSQL.
     *
     * <p>Kiểm SQLSTATE cùng schema, bảng và tên constraint.
     * Không suy đoán nguyên nhân bằng cách tìm chữ trong thông báo lỗi.
     *
     * @param action thao tác phải bị CSDL từ chối
     * @param expectedSqlState SQLSTATE mong đợi
     * @param expectedConstraint tên ràng buộc mong đợi trong thông tin lỗi
     * @return thông tin lỗi để test kiểm thêm tên cột khi cần
     */
    private static ServerErrorMessage assertDatabaseViolation(
            Executable action,
            String expectedSqlState,
            String expectedConstraint
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
        assertEquals("vehicle", details.getSchema());
        assertEquals("vehicle", details.getTable());
        assertEquals(expectedConstraint, details.getConstraint());

        return details;
    }
}
