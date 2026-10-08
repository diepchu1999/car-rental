package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra cấu trúc dữ liệu khi tạo mới và khôi phục aggregate Vehicle.
 *
 * <p>Mã xe theo database-guideline mục 2.
 * Loại sở hữu theo BR-001, liên kết chi nhánh theo BR-003
 * và loại nhiên liệu theo BR-410.
 *
 * <p>Khôi phục dữ liệu không thực hiện lại bước duyệt theo BR-005.
 * Hồ sơ giấy tờ đã hết hạn vẫn phải biểu diễn được.
 *
 * <p>Test không kiểm tính duy nhất của mã hoặc biển số,
 * không kiểm chi nhánh tồn tại và không truy cập database.
 */
class VehicleConstructionTest {

    private static final String CODE = "XE-8KQ4M2";

    private static final String PLATE_NUMBER = "51H-123.45";

    private static final Long BRANCH_ID = 42L;

    private static final VehicleDocuments DOCUMENTS =
            new VehicleDocuments(
                    LocalDate.of(2000, 1, 1),
                    LocalDate.of(2000, 1, 2)
            );

    private static final String INVALID_CODE_MESSAGE =
            "code must contain XE- followed by six uppercase ASCII letters or digits.";

    /**
     * Cung cấp mã xe thiếu hoặc sai định dạng cùng thông báo mong đợi.
     *
     * <p>Kiểm tiền tố, số lượng ký tự, chữ thường,
     * khoảng trắng và ký tự ngoài bảng chữ cái ASCII.
     *
     * @return các mã không hợp lệ cùng thông báo lỗi tương ứng
     */
    private static Stream<Arguments> invalidCodes() {
        return Stream.of(
                Arguments.of(null, "code is required."),
                Arguments.of("", "code must not be blank."),
                Arguments.of(" ", "code must not be blank."),
                Arguments.of("CN-ABC123", INVALID_CODE_MESSAGE),
                Arguments.of("XE-abc123", INVALID_CODE_MESSAGE),
                Arguments.of("XE-ABC12", INVALID_CODE_MESSAGE),
                Arguments.of("XE-ABC1234", INVALID_CODE_MESSAGE),
                Arguments.of(" XE-ABC123 ", INVALID_CODE_MESSAGE),
                Arguments.of("XE-\u00C1BC123", INVALID_CODE_MESSAGE)
        );
    }

    /**
     * Chứng minh cả hai đường khởi tạo đều từ chối mã xe không hợp lệ.
     *
     * @param code mã xe không hợp lệ
     * @param expectedMessage thông báo lỗi mong đợi
     */
    @ParameterizedTest
    @MethodSource("invalidCodes")
    void rejectsInvalidCodesInBothFactories(
            String code,
            String expectedMessage
    ) {
        assertFactoriesReject(
                code,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                DOCUMENTS,
                expectedMessage
        );
    }

    /**
     * Chứng minh biển số phải có nội dung ở cả hai đường khởi tạo.
     *
     * @param plateNumber biển số null, rỗng hoặc chỉ chứa khoảng trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n"
    })
    void rejectsMissingOrBlankPlateNumbers(String plateNumber) {
        String expectedMessage = plateNumber == null
                ? "plateNumber is required."
                : "plateNumber must not be blank.";

        assertFactoriesReject(
                CODE,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                DOCUMENTS,
                expectedMessage
        );
    }

    /**
     * Chứng minh loại sở hữu không được thiếu theo cấu trúc xe của BR-001.
     */
    @Test
    void rejectsMissingOwnershipType() {
        assertFactoriesReject(
                CODE,
                PLATE_NUMBER,
                null,
                FuelType.PETROL,
                BRANCH_ID,
                DOCUMENTS,
                "ownershipType is required."
        );
    }

    /**
     * Chứng minh loại nhiên liệu không được thiếu theo BR-410.
     */
    @Test
    void rejectsMissingFuelType() {
        assertFactoriesReject(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                null,
                BRANCH_ID,
                DOCUMENTS,
                "fuelType is required."
        );
    }

    /**
     * Chứng minh đối tượng giấy tờ không được null.
     *
     * <p>Đối tượng giấy tờ tồn tại nhưng chứa ngày null là trường hợp khác,
     * đã được kiểm trong các test giấy tờ và vòng đời.
     */
    @Test
    void rejectsMissingDocumentsObject() {
        assertFactoriesReject(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                null,
                "documents is required."
        );
    }

    /**
     * Chứng minh định danh chi nhánh được cung cấp phải lớn hơn không.
     *
     * <p>Đây chỉ là kiểm cấu trúc định danh.
     * Chi nhánh có tồn tại hay không phải được application tra cứu.
     *
     * @param branchId định danh chi nhánh không hợp lệ
     */
    @ParameterizedTest
    @ValueSource(longs = {
            0L,
            -1L,
            Long.MIN_VALUE
    })
    void rejectsNonPositiveBranchIds(long branchId) {
        assertFactoriesReject(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                branchId,
                DOCUMENTS,
                "branchId must be greater than zero when provided."
        );
    }

    /**
     * Chứng minh khôi phục dữ liệu bắt buộc phải cung cấp trạng thái.
     *
     * <p>Không tự thay trạng thái thiếu bằng DRAFT hoặc ACTIVE.
     */
    @Test
    void rejectsMissingRestoredStatus() {
        assertInvalidInput(
                () -> Vehicle.restore(
                        CODE,
                        PLATE_NUMBER,
                        OwnershipType.COMPANY,
                        FuelType.PETROL,
                        BRANCH_ID,
                        null,
                        DOCUMENTS, SPECIFICATIONS
                ),
                "status is required."
        );
    }

    /**
     * Chứng minh domain giữ nguyên biển số có nội dung.
     *
     * <p>Không tự cắt khoảng trắng hoặc chuyển chữ thường thành chữ hoa
     * khi chưa có quy tắc chuẩn hóa được chốt.
     */
    @Test
    void preservesPlateNumberWithoutNormalization() {
        String plateNumber = " 51h-123.45 ";

        Vehicle draft = Vehicle.createDraft(
                CODE,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                DOCUMENTS, SPECIFICATIONS
        );

        Vehicle restored = Vehicle.restore(
                CODE,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                VehicleStatus.ACTIVE,
                DOCUMENTS, SPECIFICATIONS
        );

        assertEquals(plateNumber, draft.plateNumber());
        assertEquals(plateNumber, restored.plateNumber());
        assertEquals(VehicleStatus.DRAFT, draft.status());
        assertEquals(VehicleStatus.ACTIVE, restored.status());
    }

    /**
     * Chứng minh khôi phục giữ nguyên dữ liệu và trạng thái đã lưu.
     *
     * <p>Các ngày giấy tờ được cố định trong quá khứ.
     * Khôi phục không duyệt lại xe hoặc từ chối hồ sơ vì hết hạn.
     *
     * <p>Việc biểu diễn trạng thái không đồng nghĩa cung cấp
     * thao tác chuyển đến trạng thái đó trong task hiện tại.
     *
     * @param status trạng thái cần khôi phục từ dữ liệu đã lưu
     */
    @ParameterizedTest
    @EnumSource(VehicleStatus.class)
    void restoresStoredStateWithoutRecheckingDocumentExpiry(
            VehicleStatus status
    ) {
        Vehicle restored = Vehicle.restore(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.HYBRID,
                BRANCH_ID,
                status,
                DOCUMENTS, SPECIFICATIONS
        );

        assertEquals(CODE, restored.code());
        assertEquals(PLATE_NUMBER, restored.plateNumber());
        assertEquals(OwnershipType.COMPANY, restored.ownershipType());
        assertEquals(FuelType.HYBRID, restored.fuelType());
        assertEquals(BRANCH_ID, restored.branchId());
        assertEquals(status, restored.status());
        assertSame(DOCUMENTS, restored.documents());
    }

    /**
     * Kiểm cùng một dữ liệu sai ở cả đường tạo mới và đường khôi phục.
     *
     * <p>Trạng thái khôi phục được giữ hợp lệ để lỗi chỉ đến
     * từ trường dữ liệu mà kịch bản đang kiểm.
     *
     * @param code mã xe cần kiểm
     * @param plateNumber biển số cần kiểm
     * @param ownershipType loại sở hữu cần kiểm
     * @param fuelType loại nhiên liệu cần kiểm
     * @param branchId định danh chi nhánh cần kiểm
     * @param documents đối tượng giấy tờ cần kiểm
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertFactoriesReject(
            String code,
            String plateNumber,
            OwnershipType ownershipType,
            FuelType fuelType,
            Long branchId,
            VehicleDocuments documents,
            String expectedMessage
    ) {
        assertInvalidInput(
                () -> Vehicle.createDraft(
                        code,
                        plateNumber,
                        ownershipType,
                        fuelType,
                        branchId,
                        documents, SPECIFICATIONS
                ),
                expectedMessage
        );

        assertInvalidInput(
                () -> Vehicle.restore(
                        code,
                        plateNumber,
                        ownershipType,
                        fuelType,
                        branchId,
                        VehicleStatus.DRAFT,
                        documents, SPECIFICATIONS
                ),
                expectedMessage
        );
    }

    /**
     * Kiểm thao tác bị từ chối với đúng lỗi đầu vào.
     *
     * @param action thao tác khởi tạo cần kiểm
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
