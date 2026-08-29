package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.VehiclePlateConflictException;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng phần chèn xe của persistence adapter bằng PostgreSQL thật.
 *
 * <p>Kiểm dữ liệu theo BR-001, BR-003, BR-005 và BR-410.
 * Tính duy nhất của mã và biển số tuân theo database-guideline.
 *
 * <p>Thao tác ghi và đọc đều đi qua các port với adapter thật.
 * Không dùng SQL chèn dữ liệu mẫu để thay thế đường ghi cần kiểm.
 *
 * <p>Mỗi test có transaction riêng và được rollback.
 * Sau lỗi PostgreSQL, test chỉ kiểm exception, không chạy thêm SQL
 * trong transaction đã lỗi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleWriteAdapterIntegrationTest {

    private static final String VEHICLE_CODE = "XE-WRIT01";
    private static final String OTHER_VEHICLE_CODE = "XE-WRIT02";

    private static final String PLATE_NUMBER = "51H-123.45";
    private static final String OTHER_PLATE_NUMBER = "51H-678.90";

    private static final Long BRANCH_ID = 42L;

    private static final LocalDate INSPECTION_EXPIRES_ON =
            LocalDate.of(2030, 5, 10);

    private static final LocalDate LIABILITY_INSURANCE_EXPIRES_ON =
            LocalDate.of(2031, 8, 20);

    @Autowired
    private WriteVehiclePort writeVehiclePort;

    @Autowired
    private ReadVehiclePort readVehiclePort;

    /**
     * Chứng minh adapter lưu đầy đủ các trường của xe nháp.
     *
     * <p>Phủ cả ngày có giá trị, ngày null và tham chiếu chi nhánh null.
     * Hai ngày khác nhau giúp phát hiện việc truyền nhầm tham số SQL.
     *
     * @param vehicle xe nháp cần chèn và đọc lại
     */
    @ParameterizedTest
    @MethodSource("draftVehicles")
    void insertsDraftAndPreservesAllFields(Vehicle vehicle) {
        assertTrue(writeVehiclePort.insert(vehicle));

        assertStoredVehicleMatches(vehicle);
    }

    /**
     * Chứng minh trùng mã trả false, không ghi đè và không làm hỏng transaction.
     *
     * <p>Dữ liệu xung đột dùng biển số khác để chỉ thử trùng mã.
     * Sau lần bị bỏ qua, tiếp tục chèn một xe khác dùng chính biển số đó.
     */
    @Test
    void skipsDuplicateCodeWithoutChangingExistingVehicleOrAbortingTransaction() {
        Vehicle original = companyDraft(
                VEHICLE_CODE,
                PLATE_NUMBER
        );

        assertTrue(writeVehiclePort.insert(original));

        VehicleDetail originalDetail =
                assertStoredVehicleMatches(original);

        Vehicle conflicting = Vehicle.createDraft(
                original.code(),
                OTHER_PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.ELECTRIC,
                BRANCH_ID + 1L,
                new VehicleDocuments(null, null)
        );

        assertFalse(writeVehiclePort.insert(conflicting));

        assertEquals(
                originalDetail,
                assertStoredVehicleMatches(original)
        );

        Vehicle another = companyDraft(
                OTHER_VEHICLE_CODE,
                OTHER_PLATE_NUMBER
        );

        assertTrue(writeVehiclePort.insert(another));

        assertStoredVehicleMatches(another);
    }

    /**
     * Chứng minh trùng biển số được chuyển đúng thành lỗi thuộc hợp đồng port.
     *
     * <p>Hai xe dùng mã khác nhau để không đồng thời trùng mã nghiệp vụ.
     * Lỗi JDBC gốc phải được giữ trong cause để phục vụ chẩn đoán.
     */
    @Test
    void translatesDuplicatePlateNumberAndPreservesCause() {
        Vehicle original = companyDraft(
                VEHICLE_CODE,
                PLATE_NUMBER
        );

        assertTrue(writeVehiclePort.insert(original));

        Vehicle conflicting = companyDraft(
                OTHER_VEHICLE_CODE,
                PLATE_NUMBER
        );

        VehiclePlateConflictException failure = assertThrows(
                VehiclePlateConflictException.class,
                () -> writeVehiclePort.insert(conflicting)
        );

        DataIntegrityViolationException storageFailure =
                assertInstanceOf(
                        DataIntegrityViolationException.class,
                        failure.getCause()
                );

        assertDatabaseViolation(
                storageFailure,
                "23505",
                "uq_vehicle_plate_number"
        );
    }

    /**
     * Chứng minh lỗi BR-003 không bị nhận nhầm thành lỗi trùng biển số.
     *
     * <p>Aggregate cho phép biểu diễn branchId null.
     * Trong đường tạo xe thực tế, application tra cứu chi nhánh trước;
     * ở đây cố tình bỏ thiếu để kiểm lỗi database được truyền ra ngoài.
     */
    @Test
    void propagatesCompanyBranchViolationWithoutTranslatingItToPlateConflict() {
        Vehicle vehicle = Vehicle.createDraft(
                VEHICLE_CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                null,
                new VehicleDocuments(null, null)
        );

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> writeVehiclePort.insert(vehicle)
        );

        assertDatabaseViolation(
                failure,
                "23514",
                "chk_vehicle_company_has_branch"
        );
    }

    /**
     * Cung cấp sáu xe nháp để kiểm cách truyền dữ liệu xuống JDBC.
     *
     * <p>Phủ bốn loại nhiên liệu và các tổ hợp ngày giấy tờ có thể null.
     * Xe nháp được lưu ngày đã hết hạn; điều kiện còn hạn thuộc bước duyệt.
     *
     * <p>Trường hợp PARTNER chỉ kiểm khả năng lưu dữ liệu theo BR-001,
     * không mở API tạo xe đối tác trong giai đoạn 1.
     *
     * @return các xe nháp dùng độc lập trong từng lần chạy test
     */
    private static Stream<Vehicle> draftVehicles() {
        return Stream.of(
                companyDraft(VEHICLE_CODE, PLATE_NUMBER),
                Vehicle.createDraft(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.DIESEL,
                        BRANCH_ID,
                        new VehicleDocuments(null, null)
                ),
                Vehicle.createDraft(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.ELECTRIC,
                        BRANCH_ID,
                        new VehicleDocuments(
                                INSPECTION_EXPIRES_ON,
                                null
                        )
                ),
                Vehicle.createDraft(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.HYBRID,
                        BRANCH_ID,
                        new VehicleDocuments(
                                null,
                                LIABILITY_INSURANCE_EXPIRES_ON
                        )
                ),
                Vehicle.createDraft(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.PETROL,
                        BRANCH_ID,
                        new VehicleDocuments(
                                LocalDate.of(2000, 1, 10),
                                LocalDate.of(2001, 2, 20)
                        )
                ),
                Vehicle.createDraft(
                        VEHICLE_CODE,
                        PLATE_NUMBER,
                        OwnershipType.PARTNER,
                        FuelType.HYBRID,
                        null,
                        new VehicleDocuments(
                                INSPECTION_EXPIRES_ON,
                                LIABILITY_INSURANCE_EXPIRES_ON
                        )
                )
        );
    }

    /**
     * Tạo xe công ty nháp với dữ liệu cố định dùng trong các kịch bản.
     *
     * <p>branchId là tham chiếu logic. Kiểm chi nhánh tồn tại
     * thuộc application, không thuộc bộ test persistence này.
     *
     * @param code mã nghiệp vụ dùng trong kịch bản
     * @param plateNumber biển số dùng trong kịch bản
     * @return xe công ty ở trạng thái DRAFT
     */
    private static Vehicle companyDraft(
            String code,
            String plateNumber
    ) {
        return Vehicle.createDraft(
                code,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                new VehicleDocuments(
                        INSPECTION_EXPIRES_ON,
                        LIABILITY_INSURANCE_EXPIRES_ON
                )
        );
    }

    /**
     * Đọc xe đã chèn và kiểm toàn bộ thuộc tính được adapter ghi.
     *
     * <p>ID do database sinh phải dương.
     * Các trường còn lại phải khớp aggregate đã gửi vào cổng ghi.
     *
     * @param expected aggregate chứa dữ liệu mong đợi
     * @return view đã đọc và được kiểm tra
     */
    private VehicleDetail assertStoredVehicleMatches(Vehicle expected) {
        Optional<VehicleDetail> result =
                readVehiclePort.findByCode(expected.code());

        assertTrue(
                result.isPresent(),
                "Expected vehicle to exist: " + expected.code()
        );

        VehicleDetail actual = result.orElseThrow();

        assertTrue(actual.id() > 0);

        VehicleDetail expectedDetail = new VehicleDetail(
                actual.id(),
                expected.code(),
                expected.plateNumber(),
                expected.ownershipType(),
                expected.fuelType(),
                expected.branchId(),
                expected.status(),
                expected.documents().inspectionExpiresOn(),
                expected.documents().liabilityInsuranceExpiresOn()
        );

        assertEquals(expectedDetail, actual);

        return actual;
    }

    /**
     * Kiểm nguyên nhân lỗi PostgreSQL bằng thông tin có cấu trúc.
     *
     * <p>Không chỉ kiểm có exception: SQLSTATE, schema, bảng
     * và tên constraint phải cùng khớp với tình huống cần chứng minh.
     *
     * @param failure lỗi lưu trữ trực tiếp hoặc được giữ trong cause
     * @param expectedSqlState SQLSTATE mong đợi
     * @param expectedConstraint tên constraint mong đợi
     */
    private static void assertDatabaseViolation(
            DataIntegrityViolationException failure,
            String expectedSqlState,
            String expectedConstraint
    ) {
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
    }
}