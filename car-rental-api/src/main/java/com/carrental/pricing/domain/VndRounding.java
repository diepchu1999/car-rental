package com.carrental.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Làm tròn riêng mỗi dòng tới 1.000 VND; phần từ 500 trở lên làm tròn lên (BR-236). */
public final class VndRounding {
    private static final BigDecimal UNIT = BigDecimal.valueOf(1_000); // BR-236.

    /** Tiện ích không có trạng thái hoặc instance. */
    private VndRounding() { }

    /** Không dùng số thực nhị phân; tràn miền long báo lỗi thay vì cắt giá trị. */
    public static long round(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount must not be negative.");
        }
        return amount.divide(UNIT, 0, RoundingMode.HALF_UP).multiply(UNIT).longValueExact();
    }
}
