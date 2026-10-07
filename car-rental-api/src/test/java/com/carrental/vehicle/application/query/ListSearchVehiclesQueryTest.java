package com.carrental.vehicle.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng đầu vào BR-018/126 trước khi chạm persistence. */
class ListSearchVehiclesQueryTest {
    /** Chỉ chuẩn hóa đầu cuối, giữ kiểu chữ để PostgreSQL so sánh thống nhất hai vế. */
    @Test
    void copiesIdsAndNormalizesText() {
        var ids = new ArrayList<>(List.of(42L, 42L, 43L));
        var query = ListSearchVehiclesQuery.from(ids, RentalType.DAILY, 5,
                "AUTOMATIC", "PETROL", " \tToYoTa\n", " Vios ", true);
        ids.clear();
        assertEquals(List.of(42L, 43L), query.branchIds());
        assertEquals("ToYoTa", query.make());
        assertEquals("Vios", query.model());
        assertEquals("AUTOMATIC", query.transmission().name());
        assertEquals("PETROL", query.fuelType().name());
        assertEquals(Boolean.TRUE, query.collateralFree());
        assertThrows(UnsupportedOperationException.class, () -> query.branchIds().clear());
    }

    /** Chi nhánh rỗng hợp lệ nhưng không đồng nghĩa tìm toàn bộ xe. */
    @Test
    void acceptsEmptyScopeAndAbsentFilters() {
        var query = ListSearchVehiclesQuery.from(List.of(), RentalType.DAILY, null,
                null, null, null, null, null);
        assertTrue(query.branchIds().isEmpty());
        assertNull(query.seats());
        assertNull(query.transmission());
        assertNull(query.fuelType());
        assertNull(query.make());
        assertNull(query.model());
        assertNull(query.collateralFree());
    }

    /** Các số chỗ trong BR-018 đều dùng được làm bộ lọc. */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 16})
    void acceptsSupportedSeats(int seats) {
        assertEquals(Integer.valueOf(seats), ListSearchVehiclesQuery.from(List.of(42L),
                RentalType.DAILY, seats, null, null, null, null, null).seats());
    }

    /** Số chỗ sai phải có đúng mã lỗi hiện hành, không bị coi như không lọc. */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 6, 17})
    void rejectsInvalidSeats(int seats) {
        var error = assertThrowsExactly(DomainException.class, () ->
                ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY, seats,
                        null, null, null, null, null));
        assertEquals(ErrorCode.VEHICLE_INVALID_SEATS, error.errorCode());
    }

    /** Danh sách thiếu hoặc chứa ID null/không dương không đi tới SQL. */
    @Test
    void rejectsMissingOrInvalidScopeAndRentalType() {
        assertInvalid(() -> ListSearchVehiclesQuery.from(null, RentalType.DAILY, null,
                null, null, null, null, null));
        for (List<Long> ids : List.of(Arrays.asList(42L, null), List.of(0L), List.of(-1L))) {
            assertInvalid(() -> ListSearchVehiclesQuery.from(ids, RentalType.DAILY, null,
                    null, null, null, null, null));
        }
        assertInvalid(() -> ListSearchVehiclesQuery.from(List.of(), null, null,
                null, null, null, null, null));
    }

    /** Không chấp nhận enum lạ, tên sai kiểu chữ hoặc chuỗi trắng như một bộ lọc vắng mặt. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "unknown", "automatic"})
    void rejectsInvalidEnums(String value) {
        assertInvalid(() -> ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY,
                null, value, null, null, null, null));
        assertInvalid(() -> ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY,
                null, null, value, null, null, null));
    }

    /** Bộ lọc hãng/dòng đã gửi nhưng rỗng phải báo lỗi đầu vào. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankText(String value) {
        assertInvalid(() -> ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY,
                null, null, null, value, null, null));
        assertInvalid(() -> ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY,
                null, null, null, null, value, null));
    }

    /** Constructor trực tiếp cũng không bỏ qua validation dành cho factory. */
    @Test
    void directConstructorCannotBypassValidation() {
        assertInvalid(() -> new ListSearchVehiclesQuery(List.of(0L), RentalType.DAILY,
                null, null, null, null, null, null));
    }

    /** Kiểm chính xác nhóm đầu vào và mã INVALID_REQUEST. */
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, error.category());
    }
}
