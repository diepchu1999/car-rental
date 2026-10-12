package com.carrental.pricing.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import static com.carrental.pricing.domain.QuoteTestFixtures.CREATED_AT;
import static org.junit.jupiter.api.Assertions.*;

/** Vi phạm từng CHECK/UNIQUE/FK V009 trên PostgreSQL thật, khẳng định SQLSTATE và tên constraint chính xác. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class QuoteConstraintIntegrationTest {
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader loader;

    /** Giá trị sai bị chính constraint tương ứng chặn, không dựa vào việc domain ném exception. */
    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsInvalidHeader(String field, Object value, String constraint) {
        var parameters = header().addValue(field, value, type(value));
        violation(() -> jdbc.queryForList(loader.load("sql/pricing/insert_quote_for_constraint_test.sql"),
                parameters, Long.class), "23514", constraint, "quote");
    }

    /** Hình dạng từng loại dòng, số tiền và loại ngày đều được CSDL bảo vệ. */
    @ParameterizedTest
    @MethodSource("invalidLines")
    void rejectsInvalidLine(String field, Object value, String constraint) {
        long quoteId = insertHeader();
        var parameters = line(quoteId).addValue(field, value, type(value));
        violation(() -> jdbc.update(loader.load("sql/pricing/insert_quote_line_for_constraint_test.sql"), parameters),
                "23514", constraint, "quote_line");
    }

    /** Mã báo giá duy nhất là ràng buộc thật, không chỉ do generator hoặc adapter che xung đột. */
    @Test
    void duplicateQuoteCodeViolatesUniqueConstraint() {
        insertHeader();
        violation(this::insertHeader, "23505", "uq_quote_code", "quote");
    }

    /** Dòng không được trùng vị trí; điều này giữ đúng thứ tự và số dòng khi đọc. */
    @Test
    void duplicateLinePositionViolatesUniqueConstraint() {
        long id = insertHeader();
        var sql = loader.load("sql/pricing/insert_quote_line_for_constraint_test.sql");
        assertEquals(1, jdbc.update(sql, line(id)));
        violation(() -> jdbc.update(sql, line(id)), "23505", "uq_quote_line_position", "quote_line");
    }

    /** FK nội bộ không cho dòng mồ côi, không cần FK sang vehicle hoặc config. */
    @Test
    void orphanLineViolatesInternalForeignKey() {
        violation(() -> jdbc.update(loader.load("sql/pricing/insert_quote_line_for_constraint_test.sql"),
                line(Long.MAX_VALUE)), "23503", "fk_quote_line_quote", "quote_line");
    }

    /** Snapshot báo giá bất biến được chặn cả khi SQL gán lại cùng giá trị. */
    @Test
    void savedHeaderCannotBeUpdated() {
        insertHeader();
        violation(() -> jdbc.update(loader.load("sql/pricing/update_quote_for_constraint_test.sql"), header()),
                "23514", "chk_quote_immutable", "quote");
    }

    /** Không sửa được từng dòng sau khi đã lưu. */
    @Test
    void savedLineCannotBeUpdated() {
        long id = insertHeader();
        jdbc.update(loader.load("sql/pricing/insert_quote_line_for_constraint_test.sql"), line(id));
        violation(() -> jdbc.update(loader.load("sql/pricing/update_quote_line_for_constraint_test.sql"), header()),
                "23514", "chk_quote_line_immutable", "quote_line");
    }

    /** Lược đồ không có FK xuyên schema, giữ ranh giới ADR-0008 bằng kiểm metadata thật. */
    @Test
    void pricingHasNoCrossSchemaForeignKeys() {
        assertEquals(0L, jdbc.getJdbcTemplate().queryForObject(loader.load("sql/pricing/count_cross_schema_foreign_keys.sql"), Long.class));
    }

    /** Dữ liệu header đúng, không cần xe thật vì mã tham chiếu không có FK chéo schema. */
    private static MapSqlParameterSource header() {
        return new MapSqlParameterSource().addValue("code", "BG-2611-CON001", Types.VARCHAR)
                .addValue("vehicleCode", "XE-TEST01", Types.VARCHAR)
                .addValue("startAt", OffsetDateTime.parse("2026-11-13T03:00:00Z"), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endAt", OffsetDateTime.parse("2026-11-15T03:00:00Z"), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("rentalType", "DAILY", Types.VARCHAR).addValue("driveMode", "SELF_DRIVE", Types.VARCHAR)
                .addValue("pickupMethod", "BRANCH", Types.VARCHAR).addValue("dailyRate", 1_000_000L, Types.BIGINT)
                .addValue("weekendDays", "{SATURDAY,SUNDAY}", Types.VARCHAR)
                .addValue("weekendUplift", new BigDecimal("0.2"), Types.NUMERIC)
                .addValue("holidayUplift", new BigDecimal("0.5"), Types.NUMERIC)
                .addValue("insuranceCalculation", "PER_BILLING_DAY", Types.VARCHAR)
                .addValue("insuranceValue", new BigDecimal("100000"), Types.NUMERIC)
                .addValue("insuranceBillingDays", 2, Types.INTEGER)
                .addValue("quoteValiditySeconds", new BigDecimal("900"), Types.NUMERIC)
                .addValue("rentalAmount", 2_200_000L, Types.BIGINT).addValue("insuranceFee", 200_000L, Types.BIGINT)
                .addValue("totalAmount", 2_400_000L, Types.BIGINT)
                .addValue("createdAt", CREATED_AT.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAt", CREATED_AT.plusSeconds(900).atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAtSubmicroNanos", 0, Types.INTEGER);
    }

    /** Dòng nền hợp lệ; mỗi test chỉ đổi đúng trường cần kiểm. */
    private static MapSqlParameterSource line(long quoteId) {
        return new MapSqlParameterSource().addValue("quoteId", quoteId, Types.BIGINT)
                .addValue("lineNumber", 1, Types.INTEGER).addValue("type", "DAY_BLOCK", Types.VARCHAR)
                .addValue("dayType", "WEEKDAY", Types.VARCHAR)
                .addValue("startAt", OffsetDateTime.parse("2026-11-13T03:00:00Z"), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("hours", 24, Types.INTEGER).addValue("dailyRate", 1_000_000L, Types.BIGINT)
                .addValue("uplift", BigDecimal.ZERO, Types.NUMERIC).addValue("amount", 1_000_000L, Types.BIGINT);
    }

    /** Chèn trực tiếp để kiểm UNIQUE, không dùng đường ON CONFLICT của production. */
    private long insertHeader() {
        return jdbc.queryForObject(loader.load("sql/pricing/insert_quote_for_constraint_test.sql"), header(), Long.class);
    }

    /** Kiểu JDBC tường minh, kể cả chuỗi NaN/Infinity cố tình chuyển xuống NUMERIC trong SQL test. */
    private static int type(Object value) {
        if (value instanceof BigDecimal) return Types.NUMERIC;
        if (value instanceof Long) return Types.BIGINT;
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof OffsetDateTime) return Types.TIMESTAMP_WITH_TIMEZONE;
        return Types.VARCHAR;
    }

    /** Kiểm đúng SQLSTATE, schema/table và tên constraint; không chạy SQL tiếp khi transaction đã lỗi. */
    private static void violation(Runnable operation, String state, String constraint, String table) {
        var failure = assertThrows(DataIntegrityViolationException.class, operation::run);
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals(state, cause.getSQLState());
        assertNotNull(cause.getServerErrorMessage());
        assertEquals("pricing", cause.getServerErrorMessage().getSchema());
        assertEquals(table, cause.getServerErrorMessage().getTable());
        assertEquals(constraint, cause.getServerErrorMessage().getConstraint());
    }

    /** Mọi CHECK header đều có trường hợp sai, gồm số NUMERIC đặc biệt không được xem là giá hợp lệ. */
    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("code", "BG-2613-CON001", "chk_quote_code_format"),
                Arguments.of("vehicleCode", "123", "chk_quote_vehicle_code"),
                Arguments.of("endAt", OffsetDateTime.parse("2026-11-13T03:00:00Z"), "chk_quote_period"),
                Arguments.of("startAt", "infinity", "chk_quote_period"),
                Arguments.of("rentalType", "MONTHLY", "chk_quote_rental_type"),
                Arguments.of("driveMode", "WITH_DRIVER", "chk_quote_drive_mode"),
                Arguments.of("pickupMethod", "DELIVERY", "chk_quote_pickup_method"),
                Arguments.of("dailyRate", 1500L, "chk_quote_daily_rate"),
                Arguments.of("dailyRate", 0L, "chk_quote_daily_rate"),
                Arguments.of("weekendDays", "{}", "chk_quote_weekend_days"),
                Arguments.of("weekendDays", "{UNKNOWN}", "chk_quote_weekend_days"),
                Arguments.of("weekendUplift", new BigDecimal("-0.1"), "chk_quote_uplifts"),
                Arguments.of("holidayUplift", "NaN", "chk_quote_uplifts"),
                Arguments.of("insuranceCalculation", "UNKNOWN", "chk_quote_insurance"),
                Arguments.of("insuranceValue", BigDecimal.ZERO, "chk_quote_insurance"),
                Arguments.of("insuranceValue", new BigDecimal("100000.1"), "chk_quote_insurance"),
                Arguments.of("insuranceValue", "Infinity", "chk_quote_insurance"),
                Arguments.of("insuranceBillingDays", 0, "chk_quote_insurance"),
                Arguments.of("quoteValiditySeconds", BigDecimal.ZERO, "chk_quote_expiry"),
                Arguments.of("expiresAt", CREATED_AT.atOffset(ZoneOffset.UTC), "chk_quote_expiry"),
                Arguments.of("expiresAtSubmicroNanos", 1000, "chk_quote_expiry"),
                Arguments.of("rentalAmount", 0L, "chk_quote_amounts"),
                Arguments.of("insuranceFee", 1500L, "chk_quote_amounts"),
                Arguments.of("totalAmount", 2_401_000L, "chk_quote_amounts"));
    }

    /** Mỗi CHECK dòng được kiểm độc lập trên một header hợp lệ. */
    private static Stream<Arguments> invalidLines() {
        return Stream.of(
                Arguments.of("lineNumber", 0, "chk_quote_line_position"),
                Arguments.of("type", "UNKNOWN", "chk_quote_line_type_hours"),
                Arguments.of("hours", 25, "chk_quote_line_type_hours"),
                Arguments.of("dayType", "UNKNOWN", "chk_quote_line_day"),
                Arguments.of("uplift", new BigDecimal("0.2"), "chk_quote_line_day"),
                Arguments.of("uplift", "NaN", "chk_quote_line_day"),
                Arguments.of("startAt", "infinity", "chk_quote_line_start"),
                Arguments.of("dailyRate", 1500L, "chk_quote_line_rate"),
                Arguments.of("amount", 1500L, "chk_quote_line_amount"));
    }
}
