package com.carrental.pricing.adapter.out.persistence;

import com.carrental.pricing.application.port.out.WriteQuotePort;
import com.carrental.pricing.domain.Quote;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

/** Ghi header và mọi dòng trong transaction của service, không tự commit hoặc mở transaction riêng. */
@Repository
class QuoteWriteAdapter implements WriteQuotePort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String insert;
    private final String insertLine;

    /** Tải SQL một lần khi tạo adapter. */
    QuoteWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc; insert = loader.load(QuoteSqlPaths.INSERT); insertLine = loader.load(QuoteSqlPaths.INSERT_LINE);
    }

    /** False chỉ cho uq_quote_code; lỗi dòng hoặc số dòng bất thường truyền ra để rollback cả header. */
    @Override
    public boolean insert(Quote quote) {
        var snapshot = quote.snapshot();
        String days = snapshot.weekendDays().stream().sorted().map(Enum::name).collect(Collectors.joining(",", "{", "}"));
        var validity = snapshot.quoteValidity();
        var parameters = new MapSqlParameterSource()
                .addValue("code", quote.code(), Types.VARCHAR).addValue("vehicleCode", quote.vehicleCode(), Types.VARCHAR)
                .addValue("startAt", timestamp(quote.startAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endAt", timestamp(quote.endAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("rentalType", quote.rentalType().name(), Types.VARCHAR)
                .addValue("driveMode", quote.driveMode().name(), Types.VARCHAR)
                .addValue("pickupMethod", quote.pickupMethod().name(), Types.VARCHAR)
                .addValue("dailyRate", snapshot.dailyRate(), Types.BIGINT).addValue("weekendDays", days, Types.VARCHAR)
                .addValue("weekendUplift", snapshot.weekendUplift(), Types.NUMERIC)
                .addValue("holidayUplift", snapshot.holidayUplift(), Types.NUMERIC)
                .addValue("insuranceCalculation", snapshot.insurance().calculation().name(), Types.VARCHAR)
                .addValue("insuranceValue", snapshot.insurance().value(), Types.NUMERIC)
                .addValue("insuranceBillingDays", snapshot.insuranceBillingDays(), Types.INTEGER)
                .addValue("quoteValiditySeconds", BigDecimal.valueOf(validity.getSeconds())
                        .add(BigDecimal.valueOf(validity.getNano(), 9)), Types.NUMERIC)
                .addValue("rentalAmount", quote.price().rentalAmount(), Types.BIGINT)
                .addValue("insuranceFee", quote.price().insuranceFee(), Types.BIGINT)
                .addValue("totalAmount", quote.price().totalAmount(), Types.BIGINT)
                .addValue("createdAt", timestamp(quote.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAt", timestamp(quote.expiresAt().truncatedTo(ChronoUnit.MICROS)), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAtSubmicroNanos", quote.expiresAt().getNano() % 1_000, Types.INTEGER);
        var ids = jdbc.queryForList(insert, parameters, Long.class);
        if (ids.isEmpty()) return false;
        if (ids.size() != 1) throw new IncorrectUpdateSemanticsDataAccessException("Expected exactly one inserted quote.");
        var lines = new MapSqlParameterSource[quote.price().lines().size()];
        for (int index = 0; index < lines.length; index++) {
            var line = quote.price().lines().get(index);
            lines[index] = new MapSqlParameterSource().addValue("quoteId", ids.getFirst(), Types.BIGINT)
                    .addValue("lineNumber", index + 1, Types.INTEGER).addValue("type", line.type().name(), Types.VARCHAR)
                    .addValue("dayType", line.dayType().name(), Types.VARCHAR)
                    .addValue("startAt", timestamp(line.startAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("hours", line.hours(), Types.INTEGER).addValue("dailyRate", line.dailyRate(), Types.BIGINT)
                    .addValue("uplift", line.uplift(), Types.NUMERIC).addValue("amount", line.amount(), Types.BIGINT);
        }
        for (int count : jdbc.batchUpdate(insertLine, lines)) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IncorrectUpdateSemanticsDataAccessException("Expected exactly one inserted quote line.");
            }
        }
        return true;
    }

    /** JDBC nhận timestamptz tường minh, không phụ thuộc timezone của JVM. */
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
