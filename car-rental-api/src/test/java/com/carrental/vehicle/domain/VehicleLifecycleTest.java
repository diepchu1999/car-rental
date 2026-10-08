package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra vòng đời gửi duyệt và phê duyệt xe theo BR-010.
 *
 * <p>Giấy tờ phải đáp ứng BR-005 trước khi xe chuyển sang ACTIVE.
 * Các thao tác phải giữ nguyên loại sở hữu theo BR-001.
 *
 * <p>Test chỉ gọi domain thuần Java, không dùng Spring hoặc database.
 * Việc kiểm phân quyền và cập nhật đồng thời không thuộc nhóm test này.
 */
class VehicleLifecycleTest {

    private static final String CODE = "XE-8KQ4M2";

    private static final String PLATE_NUMBER = "51H-123.45";

    private static final Long BRANCH_ID = 42L;

    private static final LocalDate APPROVAL_DATE =
            LocalDate.of(2026, 9, 20);

    /**
     * Chứng minh xe mới đi đúng luồng bản nháp, chờ duyệt và hoạt động.
     *
     * <p>Kiểm với mọi loại nhiên liệu theo BR-410.
     * Mỗi bước tạo đối tượng mới, không thay đổi đối tượng trước đó
     * hoặc làm mất các dữ liệu khác của xe.
     *
     * @param fuelType loại nhiên liệu của xe trong kịch bản
     */
    @ParameterizedTest
    @EnumSource(FuelType.class)
    void advancesThroughApprovalFlowWithoutChangingExistingInstances(
            FuelType fuelType
    ) {
        VehicleDocuments documents = new VehicleDocuments(
                APPROVAL_DATE.plusDays(1),
                APPROVAL_DATE.plusDays(30)
        );

        Vehicle draft = createDraft(fuelType, documents);
        Vehicle pending = draft.submitForApproval();
        Vehicle active = pending.approve(APPROVAL_DATE);

        assertNotSame(draft, pending);
        assertNotSame(pending, active);

        assertEquals(VehicleStatus.DRAFT, draft.status());
        assertEquals(VehicleStatus.PENDING_APPROVAL, pending.status());
        assertEquals(VehicleStatus.ACTIVE, active.status());

        assertVehicleData(draft, fuelType, documents);
        assertVehicleData(pending, fuelType, documents);
        assertVehicleData(active, fuelType, documents);
    }

    /**
     * Chứng minh chỉ xe DRAFT được gửi duyệt.
     *
     * <p>Các trạng thái khác chỉ được dùng làm dữ liệu kiểm thử
     * để chứng minh thao tác bị chặn, không mở thêm luồng nghiệp vụ.
     *
     * @param status trạng thái không được phép gửi duyệt
     */
    @ParameterizedTest
    @EnumSource(
            value = VehicleStatus.class,
            names = "DRAFT",
            mode = EnumSource.Mode.EXCLUDE
    )
    void rejectsSubmissionFromOtherStates(VehicleStatus status) {
        VehicleDocuments documents = new VehicleDocuments(
                APPROVAL_DATE.plusDays(1),
                APPROVAL_DATE.plusDays(1)
        );

        Vehicle vehicle = restoreVehicle(status, documents);

        assertRuleViolation(
                vehicle::submitForApproval,
                ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION
        );

        assertEquals(status, vehicle.status());
        assertVehicleData(vehicle, FuelType.PETROL, documents);
    }

    /**
     * Chứng minh chỉ xe PENDING_APPROVAL được phê duyệt.
     *
     * <p>Giấy tờ được cố ý để thiếu nhằm chứng minh trạng thái
     * được kiểm trước điều kiện giấy tờ.
     *
     * @param status trạng thái không được phép phê duyệt
     */
    @ParameterizedTest
    @EnumSource(
            value = VehicleStatus.class,
            names = "PENDING_APPROVAL",
            mode = EnumSource.Mode.EXCLUDE
    )
    void rejectsApprovalFromOtherStates(VehicleStatus status) {
        VehicleDocuments documents = new VehicleDocuments(null, null);

        Vehicle vehicle = restoreVehicle(status, documents);

        assertRuleViolation(
                () -> vehicle.approve(APPROVAL_DATE),
                ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION
        );

        assertEquals(status, vehicle.status());
        assertVehicleData(vehicle, FuelType.PETROL, documents);
    }

    /**
     * Cung cấp giấy tờ không đủ điều kiện duyệt và mã lỗi tương ứng.
     *
     * <p>Gồm thiếu một hoặc cả hai giấy tờ, hết hạn đúng ngày duyệt
     * ở từng giấy tờ riêng và cả hai đã hết hạn trước ngày duyệt.
     *
     * @return các hồ sơ bị từ chối cùng mã lỗi mong đợi
     */
    private static Stream<Arguments> invalidDocumentCases() {
        LocalDate futureDate = APPROVAL_DATE.plusDays(1);
        LocalDate pastDate = APPROVAL_DATE.minusDays(1);

        return Stream.of(
                Arguments.of(
                        new VehicleDocuments(null, null),
                        ErrorCode.VEHICLE_DOCUMENT_MISSING
                ),
                Arguments.of(
                        new VehicleDocuments(null, futureDate),
                        ErrorCode.VEHICLE_DOCUMENT_MISSING
                ),
                Arguments.of(
                        new VehicleDocuments(futureDate, null),
                        ErrorCode.VEHICLE_DOCUMENT_MISSING
                ),
                Arguments.of(
                        new VehicleDocuments(APPROVAL_DATE, futureDate),
                        ErrorCode.VEHICLE_DOCUMENT_EXPIRED
                ),
                Arguments.of(
                        new VehicleDocuments(futureDate, APPROVAL_DATE),
                        ErrorCode.VEHICLE_DOCUMENT_EXPIRED
                ),
                Arguments.of(
                        new VehicleDocuments(pastDate, pastDate),
                        ErrorCode.VEHICLE_DOCUMENT_EXPIRED
                )
        );
    }

    /**
     * Chứng minh aggregate thực sự kiểm giấy tờ trước khi phê duyệt.
     *
     * <p>Gọi qua Vehicle.approve thay vì gọi trực tiếp VehicleDocuments.
     * Khi bị từ chối, xe vẫn ở PENDING_APPROVAL và giữ nguyên dữ liệu.
     *
     * @param documents giấy tờ không đáp ứng điều kiện duyệt
     * @param expectedCode mã lỗi giấy tờ mong đợi
     */
    @ParameterizedTest
    @MethodSource("invalidDocumentCases")
    void rejectsInvalidDocumentsWithoutChangingPendingVehicle(
            VehicleDocuments documents,
            ErrorCode expectedCode
    ) {
        Vehicle pending = createDraft(
                FuelType.PETROL,
                documents
        ).submitForApproval();

        assertRuleViolation(
                () -> pending.approve(APPROVAL_DATE),
                expectedCode
        );

        assertEquals(VehicleStatus.PENDING_APPROVAL, pending.status());
        assertVehicleData(pending, FuelType.PETROL, documents);
    }

    /**
     * Chứng minh gửi duyệt chưa phải bước kiểm đủ giấy tờ theo BR-005.
     *
     * <p>Hồ sơ thiếu giấy tờ vẫn chuyển từ DRAFT sang PENDING_APPROVAL.
     * Điều kiện giấy tờ được áp dụng khi phê duyệt.
     */
    @Test
    void allowsSubmissionBeforeDocumentsAreComplete() {
        VehicleDocuments documents = new VehicleDocuments(null, null);

        Vehicle draft = createDraft(FuelType.PETROL, documents);
        Vehicle pending = draft.submitForApproval();

        assertNotSame(draft, pending);
        assertEquals(VehicleStatus.DRAFT, draft.status());
        assertEquals(VehicleStatus.PENDING_APPROVAL, pending.status());

        assertVehicleData(draft, FuelType.PETROL, documents);
        assertVehicleData(pending, FuelType.PETROL, documents);
    }

    /**
     * Chứng minh ngày duyệt thiếu không bị tự thay bằng ngày hệ thống.
     *
     * <p>Đây là lỗi lập trình của tầng gọi.
     * Xe vẫn giữ nguyên trạng thái chờ duyệt và các dữ liệu khác.
     */
    @Test
    void rejectsMissingApprovalDateWithoutChangingPendingVehicle() {
        VehicleDocuments documents = new VehicleDocuments(
                APPROVAL_DATE.plusDays(1),
                APPROVAL_DATE.plusDays(1)
        );

        Vehicle pending = createDraft(
                FuelType.PETROL,
                documents
        ).submitForApproval();

        NullPointerException failure = assertThrowsExactly(
                NullPointerException.class,
                () -> pending.approve(null)
        );

        assertEquals(
                "approvalDate must not be null.",
                failure.getMessage()
        );
        assertEquals(VehicleStatus.PENDING_APPROVAL, pending.status());
        assertVehicleData(pending, FuelType.PETROL, documents);
    }

    /**
     * Tạo xe công ty bản nháp với danh tính cố định cho unit test.
     *
     * @param fuelType loại nhiên liệu cần kiểm
     * @param documents thông tin giấy tờ của kịch bản
     * @return xe DRAFT được tạo bằng factory thật của domain
     */
    private static Vehicle createDraft(
            FuelType fuelType,
            VehicleDocuments documents
    ) {
        return Vehicle.createDraft(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                fuelType,
                BRANCH_ID,
                documents, SPECIFICATIONS
        );
    }

    /**
     * Khôi phục xe ở trạng thái chỉ định để kiểm các thao tác bị cấm.
     *
     * <p>Đây chỉ là fixture trong bộ nhớ, không ghi vào database
     * và không thực hiện các luồng ngoài phạm vi task.
     *
     * @param status trạng thái cần dùng làm điểm bắt đầu
     * @param documents thông tin giấy tờ của kịch bản
     * @return aggregate ở trạng thái được chỉ định
     */
    private static Vehicle restoreVehicle(
            VehicleStatus status,
            VehicleDocuments documents
    ) {
        return Vehicle.restore(
                CODE,
                PLATE_NUMBER,
                OwnershipType.COMPANY,
                FuelType.PETROL,
                BRANCH_ID,
                status,
                documents, SPECIFICATIONS
        );
    }

    /**
     * Kiểm dữ liệu xe ngoài trạng thái không bị thay đổi.
     *
     * <p>Đối tượng giấy tờ bất biến được giữ nguyên qua các bản xe.
     * Loại sở hữu phải luôn giữ giá trị COMPANY đã cung cấp.
     *
     * @param vehicle bản xe cần kiểm
     * @param expectedFuelType loại nhiên liệu ban đầu
     * @param expectedDocuments đối tượng giấy tờ ban đầu
     */
    private static void assertVehicleData(
            Vehicle vehicle,
            FuelType expectedFuelType,
            VehicleDocuments expectedDocuments
    ) {
        assertEquals(CODE, vehicle.code());
        assertEquals(PLATE_NUMBER, vehicle.plateNumber());
        assertEquals(OwnershipType.COMPANY, vehicle.ownershipType());
        assertEquals(expectedFuelType, vehicle.fuelType());
        assertEquals(BRANCH_ID, vehicle.branchId());
        assertSame(expectedDocuments, vehicle.documents());
        assertSame(SPECIFICATIONS, vehicle.specifications());
    }

    /**
     * Kiểm thao tác bị từ chối với đúng exception và thông tin lỗi nghiệp vụ.
     *
     * @param action thao tác domain cần kiểm
     * @param expectedCode mã lỗi mong đợi
     */
    private static void assertRuleViolation(
            Executable action,
            ErrorCode expectedCode
    ) {
        DomainException failure = assertThrowsExactly(
                DomainException.class,
                action
        );

        assertEquals(expectedCode, failure.errorCode());
        assertEquals(
                DomainException.Category.RULE_VIOLATION,
                failure.category()
        );
        assertEquals(
                expectedCode.defaultMessage(),
                failure.getMessage()
        );
    }
}
