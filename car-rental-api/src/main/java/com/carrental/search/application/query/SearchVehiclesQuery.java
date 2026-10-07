package com.carrental.search.application.query;

import com.carrental.search.domain.SearchSort;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import com.carrental.shared.validation.Validations;
import java.time.Instant;

/**
 * Đầu vào tìm kiếm BR-125/126, không nhận loại sở hữu và không chấp nhận khoảng vô hạn.
 * Mặc định trang 20, tối đa 100 theo api-guideline §8; bán kính mặc định thuộc cấu hình.
 * Điều kiện thuê theo giờ chi nhánh/cửa sổ đặt do booking kiểm, không lặp tại query này.
 */
public record SearchVehiclesQuery(Double latitude, Double longitude,
        Instant startInclusive, Instant endExclusive, RentalType rentalType, DriveMode driveMode,
        PickupMethod pickupMethod, Double radiusKm, VehicleSearchFilters filters,
        SearchSort sort, Integer limit, String cursor) {
    /** Kiểm cả constructor trực tiếp; null chỉ được phép với trường có mặc định hoặc tùy chọn. */
    public SearchVehiclesQuery {
        latitude = Validations.required(latitude, "latitude");
        longitude = Validations.required(longitude, "longitude");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw DomainException.invalidInput("latitude must be a finite number between -90 and 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw DomainException.invalidInput("longitude must be a finite number between -180 and 180.");
        }
        latitude = latitude == 0 ? 0.0 : latitude;
        longitude = longitude == 0 ? 0.0 : longitude;
        startInclusive = Validations.required(startInclusive, "startInclusive");
        endExclusive = Validations.required(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw DomainException.invalidInput("endExclusive must be after startInclusive.");
        }
        rentalType = Validations.required(rentalType, "rentalType");
        driveMode = Validations.required(driveMode, "driveMode");
        pickupMethod = pickupMethod == null ? PickupMethod.BRANCH : pickupMethod;
        filters = filters == null ? VehicleSearchFilters.none() : filters;
        sort = sort == null ? SearchSort.NEAREST : sort;
        limit = limit == null ? 20 : limit;
        if (limit < 1 || limit > 100) {
            throw DomainException.invalidInput("limit must be between 1 and 100.");
        }
        if (radiusKm != null && (!Double.isFinite(radiusKm) || radiusKm <= 0)) {
            throw DomainException.invalidInput("radiusKm must be finite and greater than zero.");
        }
        if (cursor != null && cursor.isBlank()) {
            throw DomainException.invalidInput("cursor must not be blank.");
        }
    }

    /** Tạo query từ giá trị thô; không phụ thuộc HTTP request hoặc DTO của adapter. */
    public static SearchVehiclesQuery from(Double latitude, Double longitude,
            Instant startInclusive, Instant endExclusive, RentalType rentalType, DriveMode driveMode,
            PickupMethod pickupMethod, Double radiusKm, VehicleSearchFilters filters,
            SearchSort sort, Integer limit, String cursor) {
        return new SearchVehiclesQuery(latitude, longitude, startInclusive, endExclusive,
                rentalType, driveMode, pickupMethod, radiusKm, filters, sort, limit, cursor);
    }
}
