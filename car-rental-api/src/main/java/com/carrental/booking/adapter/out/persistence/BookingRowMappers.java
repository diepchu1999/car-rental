package com.carrental.booking.adapter.out.persistence;

import com.carrental.booking.domain.*;
import com.carrental.booking.application.view.BookingListItem;
import com.carrental.shared.rental.DriveMode;
import com.carrental.shared.rental.PickupMethod;
import com.carrental.shared.rental.RentalType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;

/** Khôi phục nguyên snapshot đã lưu; không gọi pricing/rental/config (ADR-0014). */
final class BookingRowMappers {
    static final RowMapper<BookingListItem> LIST_ITEM = (rs, row) -> new BookingListItem(
            rs.getString("code"), rs.getString("vehicle_code"), rs.getString("branch_code"),
            instant(rs, "start_at"), instant(rs, "end_at"), BookingStatus.valueOf(rs.getString("status")),
            rs.getLong("total_amount"), rs.getLong("deposit"), rs.getLong("balance"),
            instant(rs, "hold_expires_at"), instant(rs, "created_at"));
    static final RowMapper<Header> HEADER = BookingRowMappers::header;
    static final RowMapper<BookingPriceLine> LINE = (rs, row) -> new BookingPriceLine(
            rs.getString("type"), rs.getString("day_type"), instant(rs, "start_at"), rs.getInt("hours"),
            rs.getLong("daily_rate"), rs.getBigDecimal("uplift"), rs.getLong("amount"));
    static final RowMapper<BookingIdempotency> IDEMPOTENCY = (rs, row) -> new BookingIdempotency(
            rs.getString("customer_account_code"), rs.getString("idempotency_key"), rs.getString("quote_code"),
            rs.getString("booking_code"), instant(rs, "created_at"), instant(rs, "expires_at"));

    /** Tiện ích mapping không có instance. */
    private BookingRowMappers() { }

    /** Đọc header sau khi SQL đã giới hạn đúng chủ; ID chỉ dùng ghép dòng con nội bộ. */
    private static Header header(ResultSet rs, int row) throws SQLException {
        var terms = new BookingRentalTerms(BookingJdbcValues.duration(rs.getBigDecimal("turnaround_buffer_seconds")),
                Optional.ofNullable(rs.getBigDecimal("minimum_duration_seconds")).map(BookingJdbcValues::duration),
                rs.getObject("branch_opens_at", LocalTime.class), rs.getObject("branch_closes_at", LocalTime.class),
                BookingJdbcValues.duration(rs.getBigDecimal("minimum_advance_seconds")),
                Period.ofMonths(rs.getInt("maximum_advance_months")));
        var amounts = new BookingAmounts(rs.getLong("rental_amount"), rs.getLong("insurance_fee"),
                rs.getLong("total_amount"), rs.getBigDecimal("deposit_ratio"), rs.getLong("deposit"), rs.getLong("balance"));
        return new Header(rs.getLong("id"), rs.getString("code"), rs.getString("customer_account_code"),
                rs.getString("quote_code"), rs.getString("vehicle_code"), rs.getString("branch_code"),
                rs.getString("reservation_code"), instant(rs, "start_at"), instant(rs, "end_at"),
                RentalType.valueOf(rs.getString("rental_type")), DriveMode.valueOf(rs.getString("drive_mode")),
                PickupMethod.valueOf(rs.getString("pickup_method")), rs.getLong("daily_rate"),
                rs.getString("insurance_calculation"), rs.getBigDecimal("insurance_value"),
                rs.getInt("insurance_billing_days"), amounts, terms, instant(rs, "hold_expires_at"),
                BookingStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "status_changed_at"));
    }

    /** Timestamptz về Instant, không dùng timezone mặc định. */
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class).toInstant();
    }

    /** Dữ liệu tạm của adapter; không đưa ra REST hoặc coi là domain chưa có dòng giá. */
    record Header(long id, String code, String customerAccountCode, String quoteCode, String vehicleCode,
            String branchCode, String reservationCode, Instant startAt, Instant endAt, RentalType rentalType,
            DriveMode driveMode, PickupMethod pickupMethod, long dailyRate, String insuranceCalculation,
            BigDecimal insuranceValue, int insuranceBillingDays, BookingAmounts amounts, BookingRentalTerms terms,
            Instant holdExpiresAt, BookingStatus status, Instant createdAt, Instant statusChangedAt) {
        /** Domain kiểm tính nhất quán giữa tổng và dòng; dữ liệu hỏng không biến thành Optional.empty. */
        Booking booking(List<BookingPriceLine> lines) {
            return new Booking(code, customerAccountCode, quoteCode, vehicleCode, branchCode, reservationCode,
                    startAt, endAt, rentalType, driveMode, pickupMethod,
                    new BookingPriceSnapshot(dailyRate, lines, insuranceCalculation, insuranceValue, insuranceBillingDays, amounts),
                    terms, holdExpiresAt, status, createdAt, statusChangedAt);
        }
    }
}
