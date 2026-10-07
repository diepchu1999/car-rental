package com.carrental.search.application.query;

import com.carrental.search.domain.SearchSort;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.PickupMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.carrental.search.SearchTestQueries.input;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào BR-125 và kích thước trang theo api-guideline §8, không mở database. */
class SearchVehiclesQueryTest {
    /** Áp đúng mặc định; bán kính để cấu hình quyết định, không lặp số 10 trong query. */
    @Test
    void appliesDefaultsWithoutHardcodingRadius() {
        var query = input().build();
        assertEquals(PickupMethod.BRANCH, query.pickupMethod());
        assertEquals(SearchSort.NEAREST, query.sort());
        assertEquals(Integer.valueOf(20), query.limit());
        assertEquals(VehicleSearchFilters.none(), query.filters());
        assertNull(query.radiusKm());
        assertNull(query.cursor());
    }

    /** Sáu trường bắt buộc không được thay thiếu bằng zero hoặc gói mặc định. */
    @ParameterizedTest
    @ValueSource(strings = {"latitude", "longitude", "start", "end", "rentalType", "driveMode"})
    void rejectsMissingMandatoryInput(String field) {
        var values = input();
        switch (field) {
            case "latitude" -> values.latitude = null;
            case "longitude" -> values.longitude = null;
            case "start" -> values.start = null;
            case "end" -> values.end = null;
            case "rentalType" -> values.rentalType = null;
            case "driveMode" -> values.driveMode = null;
            default -> throw new AssertionError("Unknown test field.");
        }
        assertInvalid(values::build);
    }

    /** Biên địa lý và tọa độ bằng zero là hợp lệ, không bị hiểu nhầm là thiếu. */
    @ParameterizedTest
    @CsvSource({"-90,-180", "90,180", "0,0", "-0.0,-0.0"})
    void acceptsGeographicBoundaries(double latitude, double longitude) {
        var values = input();
        values.latitude = latitude;
        values.longitude = longitude;
        assertNotNull(values.build());
    }

    /** NaN, vô cực và tọa độ ngoài miền phải bị chặn trước PostGIS. */
    @ParameterizedTest
    @CsvSource({"NaN,106", "Infinity,106", "-91,106", "91,106", "10,NaN",
            "10,-Infinity", "10,-181", "10,181"})
    void rejectsInvalidCoordinates(double latitude, double longitude) {
        var values = input();
        values.latitude = latitude;
        values.longitude = longitude;
        assertInvalid(values::build);
    }

    /** Khoảng rỗng hoặc đảo chiều không có ý nghĩa tìm lịch thuê. */
    @Test
    void rejectsEmptyAndReversedPeriods() {
        var values = input();
        values.end = values.start;
        assertInvalid(values::build);
        values.end = values.start.minusSeconds(1);
        assertInvalid(values::build);
    }

    /** Không tự cắt 101 về 100; đây là lỗi đầu vào. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101, Integer.MAX_VALUE})
    void rejectsInvalidPageSizes(int limit) {
        var values = input();
        values.limit = limit;
        assertInvalid(values::build);
    }

    /** Cả hai biên trang được nhận nguyên trạng. */
    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsPageSizeBoundaries(int limit) {
        var values = input();
        values.limit = limit;
        assertEquals(Integer.valueOf(limit), values.build().limit());
    }

    /** Bán kính không hữu hạn hoặc không dương luôn là lỗi, trần thực tế do cấu hình kiểm sau. */
    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsMalformedRadius(double radius) {
        var values = input();
        values.radiusKm = radius;
        assertInvalid(values::build);
    }

    /** Cursor có gửi nhưng trắng không bị coi là trang đầu. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankCursor(String cursor) {
        var values = input();
        values.cursor = cursor;
        assertInvalid(values::build);
    }

    /** Kiểm chính xác mã và nhóm lỗi, không chỉ bất kỳ exception nào. */
    private void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, error.category());
    }
}
