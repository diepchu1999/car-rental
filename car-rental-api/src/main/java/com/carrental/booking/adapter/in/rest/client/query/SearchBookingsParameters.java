package com.carrental.booking.adapter.in.rest.client.query;

import com.carrental.booking.application.query.SearchBookingsQuery;
import com.carrental.shared.error.DomainException;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/** Chuyển kiểu HTTP, không nhận chủ tự khai hoặc âm thầm bỏ tham số lọc không hỗ trợ. */
public final class SearchBookingsParameters {
    private static final Set<String> FIELDS = Set.of("limit", "cursor");

    /** Tiện ích chỉ dùng tại biên REST. */
    private SearchBookingsParameters() { }

    /** Chỉ thiếu limit mới dùng mặc định tại application; rỗng, lặp hoặc sai kiểu đều trả 400. */
    public static SearchBookingsQuery toQuery(MultiValueMap<String, String> parameters) {
        parameters.forEach((name, values) -> {
            if (!FIELDS.contains(name)) throw DomainException.invalidInput("Unsupported query parameter.");
            if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
                throw DomainException.invalidInput("Query parameters must have exactly one non-blank value.");
            }
        });
        Integer limit = null;
        if (parameters.containsKey("limit")) {
            try {
                limit = Integer.valueOf(parameters.getFirst("limit"));
            } catch (NumberFormatException failure) {
                throw DomainException.invalidInput("limit has an invalid format.");
            }
        }
        return SearchBookingsQuery.from(limit, parameters.getFirst("cursor"));
    }
}
