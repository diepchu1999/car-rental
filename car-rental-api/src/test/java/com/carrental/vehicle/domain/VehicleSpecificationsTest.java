package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm BR-018 bằng Java thuần, không phụ thuộc HTTP hoặc PostgreSQL. */
class VehicleSpecificationsTest {

    /** Chấp nhận đúng bốn số chỗ và cả hai hộp số, giữ nguyên hãng/dòng theo đầu vào. */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 16})
    void acceptsSupportedSeatsAndTransmissions(int seats) {
        for (Transmission transmission : Transmission.values()) {
            VehicleSpecifications value = new VehicleSpecifications(seats, transmission, " Toyota ", "Vios");
            assertEquals(Integer.valueOf(seats), value.seats());
            assertEquals(transmission, value.transmission());
            assertEquals(" Toyota ", value.make());
            assertEquals("Vios", value.model());
        }
    }

    /** Số chỗ ngoài BR-018 trả đúng mã vi phạm nghiệp vụ, không chỉ ném exception bất kỳ. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, 6, 8, 15, 17, Integer.MAX_VALUE})
    void rejectsUnsupportedSeats(int seats) {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> new VehicleSpecifications(seats, Transmission.MANUAL, "Toyota", "Vios"));
        assertEquals(ErrorCode.VEHICLE_INVALID_SEATS, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Thiếu số chỗ hoặc hộp số trả lỗi đầu vào, không tự chọn giá trị mặc định. */
    @Test
    void rejectsMissingSeatsAndTransmission() {
        assertInvalid(() -> new VehicleSpecifications(null, Transmission.MANUAL, "Toyota", "Vios"));
        assertInvalid(() -> new VehicleSpecifications(5, null, "Toyota", "Vios"));
    }

    /** Không cho phép thiếu, rỗng hoặc chỉ có khoảng trắng ở hãng và dòng. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n"})
    void rejectsMissingOrBlankMakeAndModel(String text) {
        assertInvalid(() -> new VehicleSpecifications(5, Transmission.MANUAL, text, "Vios"));
        assertInvalid(() -> new VehicleSpecifications(5, Transmission.MANUAL, "Toyota", text));
    }

    /** BR-018 chưa đặt độ dài tối đa; không vô tình giới hạn hãng hoặc dòng ở domain. */
    @Test
    void doesNotInventTextLengthLimit() {
        String text = "x".repeat(1000);
        VehicleSpecifications value = new VehicleSpecifications(5, Transmission.MANUAL, text, text);
        assertEquals(text, value.make());
        assertEquals(text, value.model());
    }

    /** Thuộc tính là bắt buộc ở cả tạo mới và khôi phục aggregate, không có đường lách nội bộ. */
    @Test
    void aggregateRequiresSpecificationsOnBothPaths() {
        VehicleDocuments documents = new VehicleDocuments(null, null);
        assertInvalid(() -> Vehicle.createDraft("XE-SPEC01", "SPEC-01", OwnershipType.COMPANY,
                FuelType.PETROL, 1L, documents, null));
        assertInvalid(() -> Vehicle.restore("XE-SPEC01", "SPEC-01", OwnershipType.COMPANY,
                FuelType.PETROL, 1L, VehicleStatus.DRAFT, documents, null));
    }

    /** Dữ liệu khác fixture mặc định phải sống qua tạo, gửi duyệt, duyệt và khôi phục. */
    @Test
    void preservesSpecificationsAcrossLifecycleAndRestore() {
        LocalDate today = LocalDate.of(2030, 1, 1);
        VehicleDocuments documents = new VehicleDocuments(today.plusDays(1), today.plusDays(2));
        VehicleSpecifications specs = new VehicleSpecifications(16, Transmission.MANUAL, "Ford", "Transit");
        Vehicle draft = Vehicle.createDraft("XE-SPEC01", "SPEC-01", OwnershipType.COMPANY,
                FuelType.DIESEL, 1L, documents, specs);
        Vehicle pending = draft.submitForApproval();
        Vehicle active = pending.approve(today);
        Vehicle restored = Vehicle.restore(active.code(), active.plateNumber(), active.ownershipType(),
                active.fuelType(), active.branchId(), active.status(), active.documents(), specs);
        for (Vehicle vehicle : new Vehicle[]{draft, pending, active, restored}) {
            assertSame(specs, vehicle.specifications());
        }
    }

    /** Kiểm đúng mã và nhóm lỗi đầu vào; không chấp nhận lỗi null ngoài dự kiến. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        DomainException failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
