package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra hành vi điều phối của service tra cứu xe.
 *
 * <p>Cổng đọc dùng test double chỉ cho phép lấy view,
 * với hành vi được kiểm soát bằng lambda.
 * Test không khởi động Spring, không kết nối cơ sở dữ liệu
 * và không kiểm chứng cơ chế transaction.
 */
class VehicleQueryServiceTest {

    /**
     * Chứng minh service gọi cổng đọc đúng một lần với đúng mã
     * và bổ sung mã chi nhánh qua directory, không kiểm lại điều kiện duyệt.
     *
     * <p>Hồ sơ DRAFT được phép chưa có đủ giấy tờ; điều kiện
     * giấy tờ của BR-005 chỉ áp dụng khi duyệt xe.
     */
    @Test
    void returnsDetailFromReadPort() {
        String code = "XE-READ01";

        VehicleDetail expected = new VehicleDetail(
                100L,
                code,
                "51H-123.45",
                OwnershipType.COMPANY,
                FuelType.PETROL,
                42L,
                VehicleStatus.DRAFT,
                LocalDate.of(2030, 5, 10),
                null,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model(), null
        );

        List<String> requestedCodes = new ArrayList<>();

        ReadVehiclePort readVehiclePort = new ViewOnlyVehicleReadPort(
                requestedCode -> {
                    requestedCodes.add(requestedCode);
                    return Optional.of(expected);
                }
        );

        BranchDirectory branches = mock(BranchDirectory.class);
        VehicleQueryService service = new VehicleQueryService(readVehiclePort, branches);

        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-READ01")));
        VehicleDetail actual = service.get(
                GetVehicleQuery.from(code)
        );

        assertEquals(expected.withBranchCode("CN-READ01"), actual);
        verify(branches).findById(42L);
        verifyNoMoreInteractions(branches);
        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh kết quả rỗng được chuyển thành đúng lỗi không tìm thấy.
     *
     * <p>Service giữ nguyên mã nhận được, không tự đổi chữ hoa,
     * chữ thường hoặc loại bỏ khoảng trắng bao quanh.
     *
     * @param code mã được cổng đọc mô phỏng là không tồn tại
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "XE-MISS01",
            "xe-abc123",
            " XE-ABC123 "
    })
    void reportsNotFoundWithoutChangingRequestedCode(String code) {
        List<String> requestedCodes = new ArrayList<>();

        ReadVehiclePort readVehiclePort = new ViewOnlyVehicleReadPort(
                requestedCode -> {
                    requestedCodes.add(requestedCode);
                    return Optional.empty();
                }
        );

        BranchDirectory branches = mock(BranchDirectory.class);
        VehicleQueryService service = new VehicleQueryService(readVehiclePort, branches);

        GetVehicleQuery query = GetVehicleQuery.from(code);

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> service.get(query)
        );

        assertEquals(
                ErrorCode.VEHICLE_NOT_FOUND,
                failure.errorCode()
        );

        assertEquals(
                DomainException.Category.NOT_FOUND,
                failure.category()
        );

        assertEquals(
                ErrorCode.VEHICLE_NOT_FOUND.defaultMessage(),
                failure.getMessage()
        );

        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh lỗi cổng đọc được truyền ra ngoài nguyên trạng.
     *
     * <p>Service không biến lỗi lưu trữ thành lỗi không tìm thấy
     * và không tự gọi lại cổng đọc khi có lỗi.
     */
    @Test
    void propagatesReadFailureWithoutRetrying() {
        String code = "XE-FAIL01";

        IllegalStateException storageFailure =
                new IllegalStateException(
                        "Simulated storage failure."
                );

        List<String> requestedCodes = new ArrayList<>();

        ReadVehiclePort readVehiclePort = new ViewOnlyVehicleReadPort(
                requestedCode -> {
                    requestedCodes.add(requestedCode);
                    throw storageFailure;
                }
        );

        BranchDirectory branches = mock(BranchDirectory.class);
        VehicleQueryService service = new VehicleQueryService(readVehiclePort, branches);

        GetVehicleQuery query = GetVehicleQuery.from(code);

        IllegalStateException actual = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.get(query)
        );

        assertSame(storageFailure, actual);
        assertEquals(List.of(code), requestedCodes);
    }
}
