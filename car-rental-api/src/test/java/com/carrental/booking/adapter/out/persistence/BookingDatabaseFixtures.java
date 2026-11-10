package com.carrental.booking.adapter.out.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.sql.Types;

/** Tham số SQL thử độc lập với domain để việc domain kiểm đúng không che mất lỗi CHECK tại CSDL. */
final class BookingDatabaseFixtures {
    /** Tiện ích không có instance. */
    private BookingDatabaseFixtures() { }

    /** Header hợp lệ, thay đúng từng cột trong test để biết constraint nào thực sự chặn. */
    static MapSqlParameterSource header() {
        return new MapSqlParameterSource()
                .addValue("code", "DT-2611-TEST01").addValue("customer_account_code", "TK-OWNER1")
                .addValue("quote_code", "BG-2611-TEST01").addValue("vehicle_code", "XE-TEST01")
                .addValue("branch_code", "CN-TEST01").addValue("reservation_code", "KL-TEST01")
                .addValue("start_at", "2026-11-13T03:00:00Z").addValue("end_at", "2026-11-14T03:00:00Z")
                .addValue("rental_type", "DAILY").addValue("drive_mode", "SELF_DRIVE").addValue("pickup_method", "BRANCH")
                .addValue("daily_rate", 1_000_000L).addValue("insurance_calculation", "PER_BILLING_DAY")
                .addValue("insurance_value", "100000").addValue("insurance_billing_days", 1)
                .addValue("rental_amount", 1_000_000L).addValue("insurance_fee", 100_000L)
                .addValue("total_amount", 1_100_000L).addValue("deposit_ratio", "0.30")
                .addValue("deposit", 330_000L).addValue("balance", 770_000L)
                .addValue("turnaround_buffer_seconds", "7200").addValue("minimum_duration_seconds", null, Types.VARCHAR)
                .addValue("branch_opens_at", "07:00:00").addValue("branch_closes_at", "22:00:00")
                .addValue("minimum_advance_seconds", "3600").addValue("maximum_advance_months", 6)
                .addValue("hold_expires_at", "2026-11-10T03:37:00Z").addValue("status", "PENDING_PAYMENT")
                .addValue("created_at", "2026-11-10T03:00:00Z").addValue("status_changed_at", "2026-11-10T03:00:00Z");
    }

    /** Dòng tiền thử đúng với header; không dùng số tiền do domain sản xuất để kiểm chính nó. */
    static MapSqlParameterSource line(long id) {
        return new MapSqlParameterSource().addValue("booking_id", id).addValue("line_number", 1)
                .addValue("type", "DAY_BLOCK").addValue("day_type", "WEEKDAY")
                .addValue("start_at", "2026-11-13T03:00:00Z").addValue("hours", 24)
                .addValue("daily_rate", 1_000_000L).addValue("uplift", "0").addValue("amount", 1_000_000L);
    }

    /** Sự kiện tạo mới có chủ, lý do và thời điểm, theo status-flow §1. */
    static MapSqlParameterSource history(long id) {
        return new MapSqlParameterSource().addValue("booking_id", id).addValue("from_status", null, Types.VARCHAR)
                .addValue("to_status", "PENDING_PAYMENT").addValue("actor_account_code", "TK-OWNER1")
                .addValue("reason", "CREATED").addValue("occurred_at", "2026-11-10T03:00:00Z");
    }

    /** Key cùng khách trong 24 giờ, chỉ chứa nội dung quoteCode của request. */
    static MapSqlParameterSource idempotency() {
        return new MapSqlParameterSource().addValue("customer_account_code", "TK-OWNER1")
                .addValue("idempotency_key", "request-1").addValue("quote_code", "BG-2611-TEST01")
                .addValue("booking_code", "DT-2611-TEST01").addValue("created_at", "2026-11-10T03:00:00Z")
                .addValue("expires_at", "2026-11-11T03:00:00Z");
    }
}
