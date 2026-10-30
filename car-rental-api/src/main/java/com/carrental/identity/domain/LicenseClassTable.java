package com.carrental.identity.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** BR-128: bảng hạng GPLX và số chỗ được phép; mọi hàng là dữ liệu, không phân nhánh theo tên hạng. */
public record LicenseClassTable(Map<String, Set<Integer>> allowedSeatsByClass) {
    /** Sao chép sâu để cấu hình không bị sửa sau khi đã đọc; không chấp nhận bảng/hàng rỗng. */
    public LicenseClassTable {
        Objects.requireNonNull(allowedSeatsByClass, "allowedSeatsByClass");
        if (allowedSeatsByClass.isEmpty()) throw new IllegalArgumentException("License class table must not be empty.");
        var copy = new HashMap<String, Set<Integer>>();
        allowedSeatsByClass.forEach((code, seats) -> {
            if (code == null || code.isBlank() || !code.equals(code.strip()) || seats == null || seats.isEmpty()
                    || seats.stream().anyMatch(seat -> seat == null || seat <= 0)) {
                throw new IllegalArgumentException("Invalid license class table entry.");
            }
            copy.put(code, Set.copyOf(seats));
        });
        allowedSeatsByClass = Map.copyOf(copy);
    }

    /** Phân biệt hạng chưa được khai báo với hạng có khai báo nhưng không phù hợp xe. */
    public boolean supportsClass(String licenseClass) {
        return allowedSeatsByClass.containsKey(Objects.requireNonNull(licenseClass, "licenseClass"));
    }

    /** Tra đúng hạng/số chỗ đã khai báo, không tự suy quan hệ hạng cao thấp hoặc cho phép hạng lạ. */
    public boolean allowsSeats(String licenseClass, int seats) {
        Objects.requireNonNull(licenseClass, "licenseClass");
        if (seats <= 0) throw new IllegalArgumentException("Seats must be positive.");
        return allowedSeatsByClass.getOrDefault(licenseClass, Set.of()).contains(seats);
    }
}
