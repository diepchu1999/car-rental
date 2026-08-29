package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng cập nhật trạng thái xe có điều kiện bằng PostgreSQL thật.
 *
 * <p>Các chuyển trạng thái phục vụ BR-010 và status-flow mục 4.
 * Domain tạo trạng thái đích hợp lệ trước khi cổng ghi lưu dữ liệu.
 *
 * <p>Chỉ trạng thái được thay đổi; danh tính, loại sở hữu theo BR-001,
 * chi nhánh, nhiên liệu và giấy tờ phải được giữ nguyên.
 *
 * <p>Mỗi test chạy trong transaction riêng và được rollback.
 * Đây là kiểm tra tuần tự, không thay thế test đồng thời thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleStatusPersistenceIntegrationTest {

    private static final String VEHICLE_CODE = "XE-STAT01";
    private static final String OTHER_VEHICLE_CODE = "XE-STAT02";
    private static final String MISSING_VEHICLE_CODE = "XE-MISS01";

    private static final String PLATE_NUMBER = "51H-123.45";
    private static final String OTHER_PLATE_NUMBER = "51H-678.90";

    private static final Long BRANCH_ID = 42L;

    private static final LocalDate APPROVAL_DATE =
            LocalDate.of(2026, 9, 20);

    @Autowired
    private WriteVehiclePort writeVehiclePort;

    @Autowired
    private ReadVehiclePort readVehiclePort;

    /**
     * Chứng minh cập nhật chỉ thành công khi trạng thái cũ còn khớp.
     *
     * <p>Xe thứ hai giúp kiểm điều kiện mã xe không bị bỏ sót.
     * Sau lần cập nhật đầu tiên, lặp lại yêu cầu cũ phải trả false
     * và không thay đổi thêm dữ liệu.
     *
     * @param initial xe có trạng thái trước thao tác
     * @param transitioned xe được domain chuyển sang trạng thái đích
     */
    @ParameterizedTest
    @MethodSource("statusTransitions")
    void updatesStatusOnceAndPreservesOtherFields(
            Vehicle initial,
            Vehicle transitioned
    ) {
        assertTrue(writeVehiclePort.insert(initial));

        VehicleDetail before = readRequiredVehicle(initial.code());

        Vehicle other = companyDraft(
                OTHER_VEHICLE_CODE,
                OTHER_PLATE_NUMBER
        );

        assertTrue(writeVehiclePort.insert(other));

        VehicleDetail otherBefore =
                readRequiredVehicle(other.code());

        assertTrue(
                writeVehiclePort.updateStatus(
                        initial.code(),
                        initial.status(),
                        transitioned.status()
                )
        );

        VehicleDetail after = readRequiredVehicle(initial.code());

        assertOnlyStatusChanged(
                before,
                after,
                transitioned.status()
        );

        assertEquals(
                otherBefore,
                readRequiredVehicle(other.code())
        );

        assertFalse(
                writeVehiclePort.updateStatus(
                        initial.code(),
                        initial.status(),
                        transitioned.status()
                )
        );

        assertEquals(
                after,
                readRequiredVehicle(initial.code())
        );

        assertEquals(
                otherBefore,
                readRequiredVehicle(other.code())
        );
    }

    /**
     * Chứng minh mã xe không tồn tại trả false và không tác động xe khác.
     *
     * <p>Xe đang có cùng trạng thái DRAFT được dùng để phát hiện
     * câu UPDATE vô tình thiếu điều kiện lọc theo mã.
     */
    @Test
    void returnsFalseForMissingVehicleWithoutChangingExistingVehicle() {
        Vehicle existing = companyDraft(
                VEHICLE_CODE,
                PLATE_NUMBER
        );

        assertTrue(writeVehiclePort.insert(existing));

        VehicleDetail before =
                readRequiredVehicle(existing.code());

        assertFalse(
                writeVehiclePort.updateStatus(
                        MISSING_VEHICLE_CODE,
                        VehicleStatus.DRAFT,
                        VehicleStatus.PENDING_APPROVAL
                )
        );

        assertEquals(
                before,
                readRequiredVehicle(existing.code())
        );
    }

    /**
     * Tạo hai cặp chuyển trạng thái hợp lệ bằng chính hành vi domain.
     *
     * <p>Ngày duyệt cố định giúp test không phụ thuộc ngày chạy thực tế.
     * Giấy tờ còn hạn tại ngày duyệt và có hai ngày khác nhau.
     *
     * @return các cặp xe trước và sau thao tác nghiệp vụ
     */
    private static Stream<Arguments> statusTransitions() {
        Vehicle draft = companyDraft(
                VEHICLE_CODE,
                PLATE_NUMBER
        );

        Vehicle pending = draft.submitForApproval();
        Vehicle active = pending.approve(APPROVAL_DATE);

        return Stream.of(
                Arguments.of(draft, pending),
                Arguments.of(pending, active)
        );
    }

    /**
     * Tạo xe công ty nháp với giấy tờ đủ điều kiện tại ngày duyệt của test.
     *
     * <p>branchId là tham chiếu logic phục vụ kiểm persistence.
     * Việc tra cứu chi nhánh tồn tại thuộc tầng application.
     *
     * @param code mã nghiệp vụ của xe mẫu
     * @param plateNumber biển số của xe mẫu
     * @return xe nháp có dữ liệu cố định
     */
    private static Vehicle companyDraft(
            String code,
            String plateNumber
    ) {
        return Vehicle.createDraft(
                code,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.HYBRID,
                BRANCH_ID,
                new VehicleDocuments(
                        APPROVAL_DATE.plusDays(30),
                        APPROVAL_DATE.plusDays(60)
                )
        );
    }

    /**
     * Đọc xe bắt buộc phải tồn tại trong kịch bản test.
     *
     * @param code mã xe đã được test chèn
     * @return thông tin chi tiết của xe
     */
    private VehicleDetail readRequiredVehicle(String code) {
        Optional<VehicleDetail> result =
                readVehiclePort.findByCode(code);

        assertTrue(
                result.isPresent(),
                "Expected vehicle to exist: " + code
        );

        return result.orElseThrow();
    }

    /**
     * Kiểm toàn bộ bản ghi sau cập nhật chỉ khác ở trạng thái.
     *
     * <p>Giữ nguyên cả ID và các thuộc tính không thuộc thao tác này.
     * So sánh record giúp phát hiện việc UPDATE ghi nhầm cột khác.
     *
     * @param before dữ liệu trước cập nhật
     * @param after dữ liệu đọc lại sau cập nhật
     * @param expectedStatus trạng thái đích mong đợi
     */
    private static void assertOnlyStatusChanged(
            VehicleDetail before,
            VehicleDetail after,
            VehicleStatus expectedStatus
    ) {
        VehicleDetail expected = new VehicleDetail(
                before.id(),
                before.code(),
                before.plateNumber(),
                before.ownershipType(),
                before.fuelType(),
                before.branchId(),
                expectedStatus,
                before.inspectionExpiresOn(),
                before.liabilityInsuranceExpiresOn()
        );

        assertEquals(expected, after);
    }
}