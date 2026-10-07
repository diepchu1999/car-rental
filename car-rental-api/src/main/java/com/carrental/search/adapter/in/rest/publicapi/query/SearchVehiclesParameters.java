package com.carrental.search.adapter.in.rest.publicapi.query;

import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.domain.SearchSort;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.springframework.util.MultiValueMap;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;

/**
 * Chuyển kiểu ở biên HTTP; không kiểm điều kiện thuê hoặc lặp quy tắc của application.
 * Phân biệt trường không gửi với trường gửi rỗng; từ chối tham số lạ/lặp để tránh bỏ lọc âm thầm.
 */
public final class SearchVehiclesParameters {
    private static final Set<String> FIELDS = Set.of("latitude", "longitude", "startInclusive",
            "endExclusive", "rentalType", "driveMode", "pickupMethod", "radiusKm", "seats",
            "transmission", "fuelType", "make", "model", "collateralFree", "sort", "limit", "cursor");

    /** Lớp tiện ích chỉ dùng ở REST adapter. */
    private SearchVehiclesParameters() {
    }

    /** Parse giá trị nguyên văn; mặc định, miền giá trị và nghiệp vụ vẫn do query/use case quyết định. */
    public static SearchVehiclesQuery toQuery(MultiValueMap<String, String> parameters) {
        parameters.forEach((key, values) -> {
            if (!FIELDS.contains(key)) {
                throw DomainException.invalidInput("Unsupported query parameter.");
            }
            if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
                throw DomainException.invalidInput(key + " must have exactly one non-blank value.");
            }
        });
        return SearchVehiclesQuery.from(
                parse(parameters, "latitude", Double::valueOf),
                parse(parameters, "longitude", Double::valueOf),
                parse(parameters, "startInclusive", Instant::parse),
                parse(parameters, "endExclusive", Instant::parse),
                parse(parameters, "rentalType", RentalType::valueOf),
                parse(parameters, "driveMode", DriveMode::valueOf),
                parse(parameters, "pickupMethod", PickupMethod::valueOf),
                parse(parameters, "radiusKm", Double::valueOf),
                new VehicleSearchFilters(parse(parameters, "seats", Integer::valueOf),
                        parameters.getFirst("transmission"), parameters.getFirst("fuelType"),
                        parameters.getFirst("make"), parameters.getFirst("model"),
                        parse(parameters, "collateralFree", SearchVehiclesParameters::parseBoolean)),
                parse(parameters, "sort", SearchSort::valueOf),
                parse(parameters, "limit", Integer::valueOf), parameters.getFirst("cursor"));
    }

    /** Không dùng Boolean.valueOf vì chuỗi sai như 'maybe' sẽ bị biến thành false âm thầm. */
    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean.");
        };
    }

    /** Trường vắng giữ null; lỗi chuyển kiểu trả 400, không để rơi thành lỗi hệ thống 500. */
    private static <T> T parse(MultiValueMap<String, String> parameters, String field, Function<String, T> converter) {
        String value = parameters.getFirst(field);
        if (value == null) {
            return null;
        }
        try {
            return converter.apply(value);
        } catch (IllegalArgumentException | DateTimeException failure) {
            throw DomainException.invalidInput(field + " has an invalid format.");
        }
    }
}
