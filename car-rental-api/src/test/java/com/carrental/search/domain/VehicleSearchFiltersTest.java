package com.carrental.search.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Bộ lọc biên search phải phù hợp hợp đồng vehicle.api theo BR-018/126. */
class VehicleSearchFiltersTest {
    /** Null giữ nghĩa không lọc, không tự gán hãng, nhiên liệu hoặc cờ thế chấp. */
    @Test
    void acceptsAbsentFilters() {
        assertEquals(new VehicleSearchFilters(null, null, null, null, null, null),
                VehicleSearchFilters.none());
    }

    /** BR-126: hai biên đều được tính, null mở biên, không bắt buộc biên lọc là bội số 1000. */
    @Test
    void priceBoundsAreInclusiveAndOptional() {
        var filters = prices(685_000L, 685_000L);
        assertTrue(filters.includesRentalAmount(685_000));
        assertFalse(filters.includesRentalAmount(684_000));
        assertFalse(filters.includesRentalAmount(686_000));
        assertTrue(prices(0L, null).includesRentalAmount(1_000));
        assertTrue(prices(null, Long.MAX_VALUE).includesRentalAmount(1_000));
        assertFalse(prices(null, 0L).includesRentalAmount(1_000));
        assertTrue(prices(684_999L, 685_001L).includesRentalAmount(685_000));
    }

    /** BR-126: âm hoặc min lớn hơn max là INVALID_REQUEST, không tự đảo/cắt giá trị. */
    @Test
    void rejectsNegativeOrReversedPriceBounds() {
        assertInvalid(() -> prices(-1L, null));
        assertInvalid(() -> prices(null, -1L));
        assertInvalid(() -> prices(2L, 1L));
    }

    /** Tạo bộ lọc chỉ có khoảng giá để cô lập nguyên nhân. */
    private VehicleSearchFilters prices(Long min, Long max) {
        return new VehicleSearchFilters(null, null, null, null, null, null, min, max);
    }

    /** Giữ nội dung và chữ hoa thường; chỉ bỏ khoảng trắng ở hai đầu. */
    @Test
    void normalizesTextWithoutChangingLiteralContent() {
        var filters = new VehicleSearchFilters(5, "AUTOMATIC", "PETROL", " Toyota% ", "\tVios_\n", false);
        assertEquals("Toyota%", filters.make());
        assertEquals("Vios_", filters.model());
        assertEquals(Boolean.FALSE, filters.collateralFree());
    }

    /** Tất cả số chỗ nghiệp vụ đều hợp lệ. */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 16})
    void acceptsSupportedSeats(int seats) {
        assertEquals(Integer.valueOf(seats), new VehicleSearchFilters(seats, null, null, null, null, null).seats());
    }

    /** Sai số chỗ có đúng mã VEHICLE_INVALID_SEATS, nhất quán API tạo xe và directory. */
    @ParameterizedTest
    @ValueSource(ints = {0, 6, 17})
    void rejectsUnsupportedSeats(int seats) {
        var error = assertThrowsExactly(DomainException.class,
                () -> new VehicleSearchFilters(seats, null, null, null, null, null));
        assertEquals(ErrorCode.VEHICLE_INVALID_SEATS, error.errorCode());
    }

    /** Không đoán enum sai hoặc bỏ qua chuỗi trắng trong hộp số/nhiên liệu. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid", "automatic"})
    void rejectsUnknownEnumFilters(String value) {
        assertInvalid(() -> new VehicleSearchFilters(null, value, null, null, null, null));
        assertInvalid(() -> new VehicleSearchFilters(null, null, value, null, null, null));
    }

    /** Hãng và dòng đã gửi nhưng trắng phải báo lỗi trước cả khi không có chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankMakeOrModel(String value) {
        assertInvalid(() -> new VehicleSearchFilters(null, null, null, value, null, null));
        assertInvalid(() -> new VehicleSearchFilters(null, null, null, null, value, null));
    }

    /** Kiểm mã lỗi ổn định cho dữ liệu không đúng hợp đồng. */
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.INVALID_REQUEST,
                assertThrowsExactly(DomainException.class, action).errorCode());
    }
}
