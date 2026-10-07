package com.carrental.branch.application.query;

import com.carrental.branch.domain.BranchLocation;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào địa lý trước khi tới PostgreSQL (BR-003, ADR-0007). */
class ListNearbyBranchesQueryTest {
    /** Giữ nguyên tâm và đơn vị mét; không áp trần bán kính thuộc search tại đây. */
    @Test
    void preservesCoordinatesAndMeterRadius() {
        ListNearbyBranchesQuery query = ListNearbyBranchesQuery.from(10.762622, 106.660172, 40_000.0);
        assertEquals(new BranchLocation(10.762622, 106.660172), query.origin());
        assertEquals(40_000.0, query.radiusMeters());
    }

    /** Mỗi trường thiếu có thông báo rõ và không thay bằng số không. */
    @ParameterizedTest
    @ValueSource(strings = {"latitude", "longitude", "radiusMeters"})
    void rejectsMissingInput(String field) {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> ListNearbyBranchesQuery.from(field.equals("latitude") ? null : 10.0,
                        field.equals("longitude") ? null : 106.0,
                        field.equals("radiusMeters") ? null : 1000.0));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(field + " is required.", failure.getMessage());
    }

    /** Cả constructor lẫn factory từ chối bán kính không dương hoặc không hữu hạn. */
    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidRadius(double radius) {
        DomainException direct = assertThrowsExactly(DomainException.class,
                () -> new ListNearbyBranchesQuery(new BranchLocation(0, 0), radius));
        DomainException factory = assertThrowsExactly(DomainException.class,
                () -> ListNearbyBranchesQuery.from(0.0, 0.0, radius));
        assertEquals(ErrorCode.INVALID_REQUEST, direct.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, factory.category());
        assertEquals(direct.getMessage(), factory.getMessage());
    }

    /** Tái sử dụng kiểm tọa độ của domain, không để PostGIS tự chuẩn hóa tọa độ sai. */
    @ParameterizedTest
    @ValueSource(doubles = {91.0, -91.0, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidLatitude(double latitude) {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> ListNearbyBranchesQuery.from(latitude, 106.0, 1000.0));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
    }

    /** Kinh độ cũng phải hữu hạn và thuộc miền địa lý. */
    @ParameterizedTest
    @ValueSource(doubles = {181.0, -181.0, Double.NaN, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLongitude(double longitude) {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> ListNearbyBranchesQuery.from(10.0, longitude, 1000.0));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
    }

    /** Constructor trực tiếp không thể bỏ qua tâm bắt buộc. */
    @Test
    void rejectsMissingOrigin() {
        DomainException failure = assertThrowsExactly(DomainException.class,
                () -> new ListNearbyBranchesQuery(null, 1000));
        assertEquals("origin is required.", failure.getMessage());
    }
}
