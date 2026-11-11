package com.carrental.booking.adapter.out.persistence;

import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.booking.domain.Booking;
import com.carrental.shared.sql.SqlLoader;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Đọc header đúng chủ và các dòng bất biến cùng schema, không định giá lại (ADR-0008/0014). */
@Repository
class BookingReadAdapter implements ReadBookingPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String find;
    private final String lines;

    /** Tải SQL một lần khi lắp bean. */
    BookingReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        find = loader.load(BookingSqlPaths.FIND_OWNED);
        lines = loader.load(BookingSqlPaths.FIND_LINES);
    }

    /** Không truy vấn dòng con khi sai chủ; không nuốt lỗi toàn vẹn hoặc dữ liệu hỏng. */
    @Override
    public Optional<Booking> loadOwned(String code, String customerAccountCode) {
        return DataAccessUtils.optionalResult(jdbc.query(find,
                Map.of("code", code, "customerAccountCode", customerAccountCode), BookingRowMappers.HEADER))
                .map(header -> header.booking(jdbc.query(lines, Map.of("bookingId", header.id()), BookingRowMappers.LINE)));
    }
}
