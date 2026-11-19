package com.carrental.booking.adapter.out.persistence;

import com.carrental.booking.application.port.out.WriteBookingIdempotencyPort;
import com.carrental.booking.application.view.BookingIdempotencyClaim;
import com.carrental.booking.domain.BookingIdempotency;
import com.carrental.shared.sql.SqlLoader;
import java.sql.Types;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UPSERT nguyên tử và khoá hàng tới commit; không làm đọc-rồi-ghi hoặc mở transaction riêng. */
@Repository
class BookingIdempotencyWriteAdapter implements WriteBookingIdempotencyPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String claim;
    private final String lock;
    private final String replace;

    /** Tải SQL; transaction do use case bên ngoài sở hữu. */
    BookingIdempotencyWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        claim = loader.load(BookingSqlPaths.CLAIM_KEY);
        lock = loader.load(BookingSqlPaths.LOCK_KEY);
        replace = loader.load(BookingSqlPaths.REPLACE_KEY_BOOKING);
    }

    /** RETURNING có hàng chỉ khi chiếm mới/thay key hết hạn; hai request trùng key tuần tự qua unique. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BookingIdempotencyClaim claim(BookingIdempotency proposed) {
        var parameters = parameters(proposed);
        var inserted = jdbc.query(claim, parameters, BookingRowMappers.IDEMPOTENCY);
        if (inserted.size() == 1) return new BookingIdempotencyClaim(true, inserted.getFirst());
        if (!inserted.isEmpty()) throw new IncorrectUpdateSemanticsDataAccessException("Expected one idempotency claim.");
        // Câu riêng có snapshot READ COMMITTED mới: thấy hàng của transaction vừa thắng sau khi đã chờ.
        var existing = jdbc.query(lock, parameters, BookingRowMappers.IDEMPOTENCY);
        if (existing.size() != 1) throw new IncorrectUpdateSemanticsDataAccessException("Expected an existing idempotency claim.");
        return new BookingIdempotencyClaim(false, existing.getFirst());
    }

    /** Đối chiếu toàn bộ claim để không thay nhầm key đã phát lại hoặc đã được tái sử dụng. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceClaimedBookingCode(BookingIdempotency expected, String replacementCode) {
        // Kiểm mã trước SQL bằng cùng hợp đồng domain; không thay thời điểm hoặc TTL.
        new BookingIdempotency(expected.customerAccountCode(), expected.key(), expected.quoteCode(), replacementCode,
                expected.createdAt(), expected.expiresAt());
        if (jdbc.update(replace, parameters(expected).addValue("replacementCode", replacementCode, Types.VARCHAR)) != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException("The idempotency claim no longer matches.");
        }
    }

    /** Timestamp và kiểu null tường minh; không ghép key/nội dung vào câu SQL hoặc thông báo lỗi. */
    private static MapSqlParameterSource parameters(BookingIdempotency record) {
        return new MapSqlParameterSource().addValue("customerAccountCode", record.customerAccountCode(), Types.VARCHAR)
                .addValue("key", record.key(), Types.VARCHAR).addValue("quoteCode", record.quoteCode(), Types.VARCHAR)
                .addValue("bookingCode", record.bookingCode(), Types.VARCHAR)
                .addValue("createdAt", BookingJdbcValues.timestamp(record.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAt", BookingJdbcValues.timestamp(record.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }
}
