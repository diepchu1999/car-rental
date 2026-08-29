package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng adapter đọc xe bằng PostgreSQL thật.
 *
 * <p>Kiểm việc đọc loại sở hữu theo BR-001, tham chiếu chi nhánh
 * theo BR-003, ngày giấy tờ theo BR-005, trạng thái theo BR-010
 * và loại nhiên liệu theo BR-410.
 *
 * <p>Dữ liệu được chèn trực tiếp bằng SQL dành cho test.
 * Việc đọc đi qua ReadVehiclePort và adapter thật của ứng dụng.
 *
 * <p>Mỗi test có transaction riêng và được rollback sau khi kết thúc.
 * Không sử dụng database hoặc volume của môi trường local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleReadAdapterIntegrationTest {

    private static final String INSERT_SQL_PATH =
            "sql/vehicle/insert_vehicle_for_constraint_test.sql";

    private static final String VEHICLE_CODE = "XE-READ01";
    private static final String OTHER_VEHICLE_CODE = "XE-OTHER1";

    private static final String PLATE_NUMBER = "51H-123.45";
    private static final String OTHER_PLATE_NUMBER = "51H-678.90";

    private static final Long BRANCH_ID = 42L;

    private static final LocalDate INSPECTION_EXPIRES_ON =
            LocalDate.of(2030, 5, 10);

    private static final LocalDate LIABILITY_INSURANCE_EXPIRES_ON =
            LocalDate.of(2031, 8, 20);

    @Autowired
    private ReadVehiclePort readVehiclePort;

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private SqlLoader sqlLoader;

    private String insertSql;

    /**
     * Tải câu SQL chuẩn bị dữ liệu, không dùng adapter ghi.
     */
    @BeforeEach
    void loadSql() {
        insertSql = sqlLoader.load(INSERT_SQL_PATH);
    }

    /**
     * Chứng minh adapter đọc đúng toàn bộ dữ liệu của xe được yêu cầu.
     *
     * <p>Chèn thêm một xe khác để kiểm câu truy vấn thực sự lọc theo mã.
     * ID mong đợi lấy từ kết quả chèn, không giả định sequence bắt đầu từ 1.
     *
     * @param ownershipType loại sở hữu đã lưu
     * @param fuelType loại nhiên liệu đã lưu
     * @param branchId tham chiếu chi nhánh, có thể null
     * @param status trạng thái đã lưu
     * @param inspectionExpiresOn ngày hết hiệu lực đăng kiểm, có thể null
     * @param liabilityInsuranceExpiresOn ngày hết hiệu lực TNDS, có thể null
     */
    @ParameterizedTest
    @MethodSource("storedVehicleSamples")
    void readsAllStoredFields(
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleStatus status,
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn
    ) {
        long expectedId = insertRawVehicle(
                VEHICLE_CODE,
                PLATE_NUMBER,
                ownershipType,
                fuelType,
                branchId,
                status,
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        insertRawVehicle(
                OTHER_VEHICLE_CODE,
                OTHER_PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                VehicleStatus.DRAFT,
                null,
                null
        );

        Optional<VehicleDetail> result =
                readVehiclePort.findByCode(VEHICLE_CODE);

        assertTrue(
                result.isPresent(),
                "Expected vehicle to exist: " + VEHICLE_CODE
        );

        VehicleDetail expected = new VehicleDetail(
                expectedId,
                VEHICLE_CODE,
                PLATE_NUMBER,
                ownershipType,
                fuelType,
                branchId,
                status,
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        assertEquals(expected, result.orElseThrow());
    }

    /**
     * Chứng minh mã không tồn tại trả Optional rỗng.
     */
    @Test
    void returnsEmptyWhenVehicleDoesNotExist() {
        Optional<VehicleDetail> result =
                readVehiclePort.findByCode("XE-NONE01");

        assertTrue(result.isEmpty());
    }

    /**
     * Chứng minh adapter tìm đúng giá trị mã được truyền vào.
     *
     * <p>Không tự đổi kiểu chữ hoặc cắt khoảng trắng.
     * Chuỗi có dấu nháy được xử lý như dữ liệu của tham số,
     * không trở thành một phần cấu trúc câu SQL.
     *
     * @param requestedCode mã không khớp với xe đã lưu
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "XE-MISS01",
            "xe-read01",
            " XE-READ01 ",
            "XE-READ01' OR '1'='1"
    })
    void returnsEmptyForNonMatchingCode(String requestedCode) {
        insertRawVehicle(
                VEHICLE_CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                VehicleStatus.DRAFT,
                INSPECTION_EXPIRES_ON,
                LIABILITY_INSURANCE_EXPIRES_ON
        );

        Optional<VehicleDetail> result =
                readVehiclePort.findByCode(requestedCode);

        assertTrue(
                result.isEmpty(),
                "Expected no vehicle for code: " + requestedCode
        );
    }

    /**
     * Chứng minh aggregate được khôi phục đúng từ dữ liệu PostgreSQL.
     *
     * <p>Kiểm loại sở hữu theo BR-001, tham chiếu chi nhánh
     * theo BR-003, giấy tờ theo BR-005, trạng thái theo BR-010
     * và loại nhiên liệu theo BR-410.
     *
     * <p>Kỳ vọng lấy trực tiếp từ dữ liệu mẫu, không chuyển đổi
     * từ VehicleDetail. Xe thứ hai giúp phát hiện truy vấn
     * không lọc đúng mã xe.
     *
     * <p>Khôi phục hồ sơ đã lưu không được chạy lại điều kiện duyệt,
     * kể cả khi hồ sơ ACTIVE có giấy tờ đã hết hạn.
     *
     * @param ownershipType loại sở hữu đã lưu
     * @param fuelType loại nhiên liệu đã lưu
     * @param branchId tham chiếu chi nhánh, có thể null
     * @param status trạng thái đã lưu
     * @param inspectionExpiresOn ngày hết hiệu lực đăng kiểm, có thể null
     * @param liabilityInsuranceExpiresOn ngày hết hiệu lực TNDS, có thể null
     */
    @ParameterizedTest
    @MethodSource("storedVehicleSamples")
    void loadsAggregateWithAllStoredFields(
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleStatus status,
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn
    ) {
        insertRawVehicle(
                VEHICLE_CODE,
                PLATE_NUMBER,
                ownershipType,
                fuelType,
                branchId,
                status,
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        insertRawVehicle(
                OTHER_VEHICLE_CODE,
                OTHER_PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                VehicleStatus.DRAFT,
                null,
                null
        );

        Optional<Vehicle> result =
                readVehiclePort.loadAggregate(VEHICLE_CODE);

        assertTrue(
                result.isPresent(),
                "Expected vehicle aggregate to exist: " + VEHICLE_CODE
        );

        Vehicle actual = result.orElseThrow();

        assertEquals(VEHICLE_CODE, actual.code());
        assertEquals(PLATE_NUMBER, actual.plateNumber());
        assertEquals(ownershipType, actual.ownershipType());
        assertEquals(fuelType, actual.fuelType());
        assertEquals(branchId, actual.branchId());
        assertEquals(status, actual.status());
        assertNotNull(actual.documents());
        assertEquals(
                inspectionExpiresOn,
                actual.documents().inspectionExpiresOn()
        );
        assertEquals(
                liabilityInsuranceExpiresOn,
                actual.documents().liabilityInsuranceExpiresOn()
        );
    }

    /**
     * Chứng minh tải aggregate của mã không tồn tại trả Optional rỗng.
     */
    @Test
    void returnsEmptyAggregateWhenVehicleDoesNotExist() {
        Optional<Vehicle> result =
                readVehiclePort.loadAggregate("XE-NONE01");

        assertTrue(result.isEmpty());
    }

    /**
     * Chứng minh tải aggregate chỉ khớp chính xác mã được truyền vào.
     *
     * <p>Không tự đổi kiểu chữ hoặc cắt khoảng trắng.
     * Chuỗi có dấu nháy phải được xử lý như dữ liệu tham số,
     * không được thay đổi điều kiện truy vấn.
     *
     * @param requestedCode mã không khớp với xe đã lưu
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "XE-MISS01",
            "xe-read01",
            " XE-READ01 ",
            "XE-READ01' OR '1'='1"
    })
    void returnsEmptyAggregateForNonMatchingCode(String requestedCode) {
        insertRawVehicle(
                VEHICLE_CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                VehicleStatus.DRAFT,
                INSPECTION_EXPIRES_ON,
                LIABILITY_INSURANCE_EXPIRES_ON
        );

        Optional<Vehicle> result =
                readVehiclePort.loadAggregate(requestedCode);

        assertTrue(
                result.isEmpty(),
                "Expected no vehicle aggregate for code: " + requestedCode
        );
    }

    /**
     * Cung cấp dữ liệu phủ hai loại sở hữu, bốn loại nhiên liệu
     * và sáu trạng thái được mô hình dữ liệu hỗ trợ.
     *
     * <p>Các ngày khác nhau giúp phát hiện ánh xạ nhầm hai cột.
     * Có đủ trường hợp cả hai ngày null, chỉ một ngày null
     * và hồ sơ ACTIVE có ngày giấy tờ đã hết hạn.
     *
     * <p>Đọc hồ sơ cũ không được kích hoạt lại điều kiện duyệt xe.
     * Dữ liệu PARTNER chỉ phục vụ kiểm ánh xạ, không mở API tạo xe đối tác.
     *
     * @return các bộ dữ liệu lưu và đọc lại
     */
    private static Stream<Arguments> storedVehicleSamples() {
        return Stream.of(
                Arguments.of(
                        OwnershipType.COMPANY,
                        FuelType.PETROL,
                        BRANCH_ID,
                        VehicleStatus.DRAFT,
                        INSPECTION_EXPIRES_ON,
                        LIABILITY_INSURANCE_EXPIRES_ON
                ),
                Arguments.of(
                        OwnershipType.COMPANY,
                        FuelType.DIESEL,
                        BRANCH_ID,
                        VehicleStatus.PENDING_APPROVAL,
                        null,
                        null
                ),
                Arguments.of(
                        OwnershipType.COMPANY,
                        FuelType.ELECTRIC,
                        BRANCH_ID,
                        VehicleStatus.ACTIVE,
                        LocalDate.of(2000, 1, 10),
                        LocalDate.of(2001, 2, 20)
                ),
                Arguments.of(
                        OwnershipType.PARTNER,
                        FuelType.HYBRID,
                        null,
                        VehicleStatus.INACTIVE,
                        INSPECTION_EXPIRES_ON,
                        null
                ),
                Arguments.of(
                        OwnershipType.PARTNER,
                        FuelType.PETROL,
                        null,
                        VehicleStatus.REJECTED,
                        null,
                        LIABILITY_INSURANCE_EXPIRES_ON
                ),
                Arguments.of(
                        OwnershipType.COMPANY,
                        FuelType.HYBRID,
                        BRANCH_ID,
                        VehicleStatus.RETIRED,
                        INSPECTION_EXPIRES_ON,
                        LIABILITY_INSURANCE_EXPIRES_ON
                )
        );
    }

    /**
     * Chèn dữ liệu mẫu và lấy ID do PostgreSQL sinh.
     *
     * <p>Khai báo kiểu SQL tường minh để truyền được các giá trị null.
     * Chỉ yêu cầu trả khoá sinh tự động của cột id.
     *
     * <p>branchId là tham chiếu logic không có khoá ngoại xuyên module.
     * Test này không kiểm sự tồn tại của chi nhánh ở tầng application.
     *
     * @param code mã xe cần chèn
     * @param plateNumber biển số cần chèn
     * @param ownershipType loại sở hữu cần lưu
     * @param fuelType loại nhiên liệu cần lưu
     * @param branchId tham chiếu chi nhánh, có thể null
     * @param status trạng thái cần lưu
     * @param inspectionExpiresOn ngày hết hiệu lực đăng kiểm, có thể null
     * @param liabilityInsuranceExpiresOn ngày hết hiệu lực TNDS, có thể null
     * @return ID thực tế của xe vừa được chèn
     */
    private long insertRawVehicle(
            String code,
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleStatus status,
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("plateNumber", plateNumber, Types.VARCHAR)
                .addValue(
                        "ownershipType",
                        ownershipType.name(),
                        Types.VARCHAR
                )
                .addValue("fuelType", fuelType.name(), Types.VARCHAR)
                .addValue("branchId", branchId, Types.BIGINT)
                .addValue("status", status.name(), Types.VARCHAR)
                .addValue(
                        "inspectionExpiresOn",
                        inspectionExpiresOn,
                        Types.DATE
                )
                .addValue(
                        "liabilityInsuranceExpiresOn",
                        liabilityInsuranceExpiresOn,
                        Types.DATE
                );

        KeyHolder generatedKeys = new GeneratedKeyHolder();

        int insertedRows = jdbcTemplate.update(
                insertSql,
                parameters,
                generatedKeys,
                new String[]{"id"}
        );

        assertEquals(1, insertedRows);

        Number generatedId = generatedKeys.getKey();

        assertNotNull(
                generatedId,
                "Expected PostgreSQL to return the generated vehicle ID."
        );
        assertTrue(generatedId.longValue() > 0);

        return generatedId.longValue();
    }
}