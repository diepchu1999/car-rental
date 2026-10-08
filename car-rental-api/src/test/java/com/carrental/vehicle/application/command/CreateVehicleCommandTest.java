package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.VehicleSpecifications;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm việc tạo command và chuyển dữ liệu đầu vào sang đối tượng giấy tờ.
 *
 * <p>Loại sở hữu theo BR-001, chi nhánh theo BR-003
 * và loại nhiên liệu theo BR-410.
 *
 * <p>Điều kiện giấy tờ tại bước duyệt theo BR-005
 * không được áp dụng sớm vào bước tạo command.
 *
 * <p>Test chỉ chạy Java thuần, không dùng Spring hoặc database.
 */
class CreateVehicleCommandTest {

    /** BR-018: factory không bỏ qua thuộc tính khác dữ liệu mẫu của các test cũ. */
    @Test
    void mapsAllSpecificationFields() {
        CreateVehicleCommand command = CreateVehicleCommand.from("SPEC-01", OwnershipType.COMPANY,
                FuelType.DIESEL, "CN-ABC123", null, null, 16,
                Transmission.MANUAL, "Ford", "Transit");
        assertEquals(new VehicleSpecifications(16,
                Transmission.MANUAL, "Ford", "Transit"), command.specifications());
    }

    /** Constructor command không được cho phép thiếu đối tượng thuộc tính BR-018. */
    @Test
    void rejectsMissingSpecificationsObject() {
        assertInvalidInput(() -> new CreateVehicleCommand(PLATE_NUMBER, OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH_CODE, DOCUMENTS, null), "specifications is required.");
    }

    /** Factory kiểm đủ cả bốn trường trước khi cho command vào use case. */
    @Test
    void factoryRequiresEverySpecificationField() {
        assertInvalidInput(() -> CreateVehicleCommand.from(PLATE_NUMBER, OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH_CODE, null, null, null, SPECIFICATIONS.transmission(), "Toyota", "Vios"),
                "seats is required.");
        assertInvalidInput(() -> CreateVehicleCommand.from(PLATE_NUMBER, OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH_CODE, null, null, 5, null, "Toyota", "Vios"),
                "transmission is required.");
        assertInvalidInput(() -> CreateVehicleCommand.from(PLATE_NUMBER, OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH_CODE, null, null, 5, SPECIFICATIONS.transmission(), null, "Vios"),
                "make is required.");
        assertInvalidInput(() -> CreateVehicleCommand.from(PLATE_NUMBER, OwnershipType.COMPANY,
                FuelType.PETROL, BRANCH_CODE, null, null, 5, SPECIFICATIONS.transmission(), "Toyota", null),
                "model is required.");
    }

    private static final String PLATE_NUMBER = "51H-123.45";

    private static final String BRANCH_CODE = "CN-ABC123";

    private static final LocalDate INSPECTION_DATE =
            LocalDate.of(2026, 10, 1);

    private static final LocalDate INSURANCE_DATE =
            LocalDate.of(2026, 11, 2);

    private static final VehicleDocuments DOCUMENTS =
            new VehicleDocuments(
                    INSPECTION_DATE,
                    INSURANCE_DATE
            );

    /**
     * Chứng minh factory và constructor giữ nguyên các giá trị đầu vào.
     *
     * <p>Kiểm mọi loại nhiên liệu theo BR-410.
     * Biển số và mã chi nhánh không bị cắt khoảng trắng hoặc đổi kiểu chữ.
     * Mã chi nhánh không bị kiểm theo định dạng sinh mã tại bước này.
     *
     * @param fuelType loại nhiên liệu cần truyền qua command
     */
    @ParameterizedTest
    @EnumSource(FuelType.class)
    void preservesInputInFactoryAndConstructor(FuelType fuelType) {
        String plateNumber = " 51h-123.45 ";
        String branchCode = " cn-abc123 ";

        CreateVehicleCommand fromFactory = CreateVehicleCommand.from(
                plateNumber,
                OwnershipType.COMPANY,
                fuelType,
                branchCode,
                INSPECTION_DATE,
                INSURANCE_DATE,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model()
        );

        CreateVehicleCommand fromConstructor = new CreateVehicleCommand(
                plateNumber,
                OwnershipType.COMPANY,
                fuelType,
                branchCode,
                DOCUMENTS, SPECIFICATIONS
        );

        assertEquals(plateNumber, fromFactory.plateNumber());
        assertEquals(OwnershipType.COMPANY, fromFactory.ownershipType());
        assertEquals(fuelType, fromFactory.fuelType());
        assertEquals(branchCode, fromFactory.branchCode());
        assertEquals(DOCUMENTS, fromFactory.documents());

        assertEquals(fromFactory, fromConstructor);
        assertSame(DOCUMENTS, fromConstructor.documents());
    }

    /**
     * Chứng minh command không tự đổi loại sở hữu do tầng gọi cung cấp.
     *
     * <p>Đây là kiểm truyền dữ liệu nội bộ theo BR-001,
     * không mở chức năng tạo xe đối tác qua HTTP.
     * API giai đoạn 1 vẫn phải gán cố định COMPANY.
     *
     * @param ownershipType loại sở hữu cần được giữ nguyên
     */
    @ParameterizedTest
    @EnumSource(OwnershipType.class)
    void preservesSuppliedOwnershipType(OwnershipType ownershipType) {
        CreateVehicleCommand command = CreateVehicleCommand.from(
                PLATE_NUMBER,
                ownershipType,
                FuelType.DIESEL,
                BRANCH_CODE,
                INSPECTION_DATE,
                INSURANCE_DATE,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model()
        );

        assertEquals(ownershipType, command.ownershipType());
    }

    /**
     * Cung cấp ngày giấy tờ chưa đủ điều kiện duyệt nhưng được lưu ở bản nháp.
     *
     * <p>Gồm thiếu cả hai ngày, thiếu từng ngày và ngày trong quá khứ.
     *
     * @return các cặp ngày phải được command giữ nguyên
     */
    private static Stream<Arguments> draftDocumentDates() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(null, INSURANCE_DATE),
                Arguments.of(INSPECTION_DATE, null),
                Arguments.of(
                        LocalDate.of(2000, 1, 1),
                        LocalDate.of(2000, 1, 2)
                )
        );
    }

    /**
     * Chứng minh command không kiểm giấy tờ thay cho bước duyệt.
     *
     * <p>Ngày thiếu phải giữ null, không được thay bằng ngày mặc định.
     * Ngày đã qua vẫn được giữ để biểu diễn hồ sơ bản nháp.
     *
     * @param inspectionExpiresOn ngày hết hạn đăng kiểm, có thể null
     * @param liabilityInsuranceExpiresOn ngày hết hạn TNDS, có thể null
     */
    @ParameterizedTest
    @MethodSource("draftDocumentDates")
    void acceptsDraftDocumentDatesWithoutApprovalValidation(
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn
    ) {
        VehicleDocuments expectedDocuments = new VehicleDocuments(
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        CreateVehicleCommand fromFactory = CreateVehicleCommand.from(
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_CODE,
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model()
        );

        CreateVehicleCommand fromConstructor = new CreateVehicleCommand(
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_CODE,
                expectedDocuments, SPECIFICATIONS
        );

        assertNotNull(fromFactory.documents());
        assertEquals(expectedDocuments, fromFactory.documents());
        assertEquals(fromFactory, fromConstructor);
        assertSame(expectedDocuments, fromConstructor.documents());
    }

    /**
     * Chứng minh biển số thiếu hoặc trắng bị từ chối ở cả hai đường tạo.
     *
     * @param plateNumber biển số không hợp lệ
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n"
    })
    void rejectsMissingOrBlankPlateNumber(String plateNumber) {
        String expectedMessage = plateNumber == null
                ? "plateNumber is required."
                : "plateNumber must not be blank.";

        assertBothPathsReject(
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_CODE,
                expectedMessage
        );
    }

    /**
     * Chứng minh mã chi nhánh thiếu hoặc trắng bị từ chối trước khi tra cứu.
     *
     * @param branchCode mã chi nhánh không hợp lệ
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n"
    })
    void rejectsMissingOrBlankBranchCode(String branchCode) {
        String expectedMessage = branchCode == null
                ? "branchCode is required."
                : "branchCode must not be blank.";

        assertBothPathsReject(
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                branchCode,
                expectedMessage
        );
    }

    /**
     * Chứng minh command không tự đặt loại sở hữu khi tầng gọi bỏ thiếu.
     */
    @Test
    void rejectsMissingOwnershipType() {
        assertBothPathsReject(
                PLATE_NUMBER,
                null,
                FuelType.PETROL,
                BRANCH_CODE,
                "ownershipType is required."
        );
    }

    /**
     * Chứng minh loại nhiên liệu là đầu vào bắt buộc.
     */
    @Test
    void rejectsMissingFuelType() {
        assertBothPathsReject(
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                null,
                BRANCH_CODE,
                "fuelType is required."
        );
    }

    /**
     * Chứng minh constructor trực tiếp không cho phép thiếu đối tượng giấy tờ.
     *
     * <p>Factory luôn tạo VehicleDocuments, kể cả khi cả hai ngày là null.
     * Vì vậy trường hợp đối tượng null được kiểm ở constructor trực tiếp.
     */
    @Test
    void rejectsMissingDocumentsObject() {
        assertInvalidInput(
                () -> new CreateVehicleCommand(
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.PETROL,
                        BRANCH_CODE,
                        null, SPECIFICATIONS
                ),
                "documents is required."
        );
    }

    /**
     * Kiểm cùng dữ liệu sai qua factory và constructor trực tiếp.
     *
     * <p>Giấy tờ được giữ cố định để lỗi chỉ đến
     * từ trường mà kịch bản đang kiểm.
     *
     * @param plateNumber biển số cần kiểm
     * @param ownershipType loại sở hữu cần kiểm
     * @param fuelType loại nhiên liệu cần kiểm
     * @param branchCode mã chi nhánh cần kiểm
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertBothPathsReject(
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            String branchCode,
            String expectedMessage
    ) {
        assertInvalidInput(
                () -> CreateVehicleCommand.from(
                        plateNumber,
                        ownershipType,
                        fuelType,
                        branchCode,
                        INSPECTION_DATE,
                        INSURANCE_DATE,
                        SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                        SPECIFICATIONS.make(), SPECIFICATIONS.model()
                ),
                expectedMessage
        );

        assertInvalidInput(
                () -> new CreateVehicleCommand(
                        plateNumber,
                        ownershipType,
                        fuelType,
                        branchCode,
                        DOCUMENTS, SPECIFICATIONS
                ),
                expectedMessage
        );
    }

    /**
     * Kiểm thao tác bị từ chối với đúng exception và thông tin lỗi đầu vào.
     *
     * @param action thao tác tạo command cần kiểm
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertInvalidInput(
            Executable action,
            String expectedMessage
    ) {
        DomainException failure = assertThrowsExactly(
                DomainException.class,
                action
        );

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                failure.category()
        );
        assertEquals(expectedMessage, failure.getMessage());
    }
}
