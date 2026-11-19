package com.carrental.booking.adapter.out.persistence;

import com.carrental.shared.sql.SqlLoader;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Seed header lịch sử trong container test để kiểm phân trang HTTP, không giả lập luồng hủy đơn. */
public final class BookingListTestFixture {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final NamedParameterJdbcTemplate jdbc;
    private final String insert;

    /** Dùng SQL fixture riêng của test, không chạm CSDL local hoặc thay code sản phẩm. */
    public BookingListTestFixture(NamedParameterJdbcTemplate jdbc, SqlLoader sql) {
        this.jdbc = jdbc;
        this.insert = sql.load("sql/booking/insert_booking_for_constraint_test.sql");
    }

    /** Commit một header CANCELLED để nhiều đơn cùng khách không vi phạm BR-131. */
    public String cancelled(String owner, Instant createdAt) {
        String suffix = String.format(Locale.ROOT, "LH%04d", SEQUENCE.incrementAndGet());
        String code = "DT-2611-" + suffix;
        var parameters = BookingDatabaseFixtures.header().addValue("code", code)
                .addValue("customer_account_code", owner).addValue("quote_code", "BG-2611-" + suffix)
                .addValue("reservation_code", "KL-" + suffix).addValue("status", "CANCELLED")
                .addValue("created_at", createdAt.toString())
                .addValue("status_changed_at", createdAt.plusSeconds(1).toString())
                .addValue("hold_expires_at", createdAt.plusSeconds(2220).toString());
        if (jdbc.queryForObject(insert, parameters, Long.class) == null) {
            throw new IllegalStateException("Booking list fixture was not inserted.");
        }
        return code;
    }
}
