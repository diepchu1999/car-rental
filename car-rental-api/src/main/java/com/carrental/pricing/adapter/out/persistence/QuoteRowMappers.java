package com.carrental.pricing.adapter.out.persistence;

import com.carrental.pricing.application.view.QuoteDetail;
import com.carrental.pricing.domain.*;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Khôi phục snapshot và từng dòng từ pricing, không tra xe hoặc đọc cấu hình mới. */
final class QuoteRowMappers {
    static final RowMapper<Header> HEADER = QuoteRowMappers::header;
    static final RowMapper<QuoteLine> LINE = (rs, number) -> new QuoteLine(
            QuoteLineType.valueOf(rs.getString("type")), DayType.valueOf(rs.getString("day_type")),
            instant(rs, "start_at"), rs.getInt("hours"), rs.getLong("daily_rate"),
            rs.getBigDecimal("uplift"), rs.getLong("amount"));

    /** Tiện ích mapping không có instance. */
    private QuoteRowMappers() { }

    /** Đọc đúng giá trị đã lưu, bảo toàn phần nano của hạn cấu hình dưới micro giây. */
    private static Header header(ResultSet rs, int number) throws SQLException {
        var array = rs.getArray("weekend_days");
        var days = Arrays.stream((String[]) array.getArray()).map(DayOfWeek::valueOf).collect(Collectors.toSet());
        array.free();
        BigDecimal seconds = rs.getBigDecimal("quote_validity_seconds");
        long whole = seconds.toBigInteger().longValueExact();
        int nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValueExact();
        var snapshot = new QuoteSnapshot(rs.getLong("daily_rate"), days,
                rs.getBigDecimal("weekend_uplift"), rs.getBigDecimal("holiday_uplift"),
                new InsuranceTerms(InsuranceCalculation.valueOf(rs.getString("insurance_calculation")),
                        rs.getBigDecimal("insurance_value")), rs.getInt("insurance_billing_days"),
                Duration.ofSeconds(whole, nanos));
        return new Header(rs.getLong("id"), rs.getString("code"), rs.getString("vehicle_code"),
                instant(rs, "start_at"), instant(rs, "end_at"), RentalType.valueOf(rs.getString("rental_type")),
                DriveMode.valueOf(rs.getString("drive_mode")), PickupMethod.valueOf(rs.getString("pickup_method")),
                snapshot, rs.getLong("rental_amount"), rs.getLong("insurance_fee"), rs.getLong("total_amount"),
                instant(rs, "created_at"), instant(rs, "expires_at").plusNanos(rs.getInt("expires_at_submicro_nanos")));
    }

    /** Chuyển timestamptz về một thời điểm độc lập timezone kết nối. */
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    /** Dữ liệu ghép nội bộ adapter; ID chỉ dùng đọc dòng con trong cùng schema. */
    record Header(long id, String code, String vehicleCode, Instant startAt, Instant endAt,
            RentalType rentalType, DriveMode driveMode, PickupMethod pickupMethod, QuoteSnapshot snapshot,
            long rentalAmount, long insuranceFee, long totalAmount, Instant createdAt, Instant expiresAt) {
        /** Xác nhận tổng khớp các dòng đã lưu trước khi trả mô hình đọc cho application. */
        QuoteDetail detail(List<QuoteLine> lines) {
            return new QuoteDetail(code, vehicleCode, startAt, endAt, rentalType, driveMode, pickupMethod,
                    snapshot, new CalculatedPrice(lines, rentalAmount, insuranceFee, totalAmount), createdAt, expiresAt);
        }
    }
}
