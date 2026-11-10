package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static com.carrental.booking.adapter.out.persistence.BookingDatabaseFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phá từng ràng buộc V016 trên PostgreSQL thật; transaction của test luôn rollback, không chạm dữ liệu local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BookingConstraintIntegrationTest {
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;

    /** Mỗi giá trị sai phải trúng đúng CHECK, không chấp nhận lỗi cú pháp hoặc sai kiểu thay thế. */
    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsInvalidBooking(String field, Object value, String constraint) {
        var parameters = header().addValue(field, value);
        violation(() -> insert(parameters), "23514", constraint, "booking");
    }

    /** Một khách không có đơn chờ thứ hai, kể cả gọi trực tiếp SQL. */
    @Test
    void databaseRejectsSecondPendingBookingForSameCustomer() {
        insert(header());
        violation(() -> insert(secondHeader()), "23505", "uq_booking_pending_customer", "booking");
    }

    /** Huỷ không giữ hạn mức đơn chờ; snapshot tiền/hạn giữ nguyên khi trạng thái thay đổi. */
    @Test
    void cancelledBookingDoesNotPreventAnotherPendingBooking() {
        long id = insert(header());
        var parameters = new MapSqlParameterSource("id", id)
                .addValue("changed_at", OffsetDateTime.parse("2026-11-10T03:05:00Z"));
        assertEquals(1, jdbc.update(load("cancel_booking_for_constraint_test"), parameters));
        var stored = jdbc.queryForMap(load("read_booking_snapshot"), parameters);
        assertEquals("CANCELLED", stored.get("status"));
        assertEquals(330_000L, ((Number) stored.get("deposit")).longValue());
        assertEquals(770_000L, ((Number) stored.get("balance")).longValue());
        assertNotNull(stored.get("hold_expires_at"));
        assertTrue(insert(secondHeader()) > 0);
    }

    /** Mã đơn và mã chỗ giữ đều duy nhất, độc lập với giới hạn theo khách. */
    @ParameterizedTest
    @CsvSource({"code,DT-2611-TEST01,uq_booking_code", "reservation_code,KL-TEST01,uq_booking_reservation_code"})
    void rejectsDuplicateReferences(String field, String value, String constraint) {
        insert(header());
        var parameters = secondHeader().addValue("customer_account_code", "TK-OTHER1").addValue(field, value);
        violation(() -> insert(parameters), "23505", constraint, "booking");
    }

    /** Sửa giá hoặc hạn sau khi đã chốt phải bị trigger chặn, dù tổng vẫn tự nhất quán. */
    @ParameterizedTest
    @ValueSource(strings = {"update_booking_snapshot_for_constraint_test", "update_booking_expiry_for_constraint_test"})
    void frozenBookingCannotBeChanged(String file) {
        long id = insert(header());
        violation(() -> jdbc.update(load(file), new MapSqlParameterSource("id", id)),
                "23514", "chk_booking_snapshot_immutable", "booking");
    }

    /** Dòng sai số giờ, loại ngày, giá hoặc số tiền bị đúng CHECK chặn. */
    @ParameterizedTest
    @MethodSource("invalidLines")
    void rejectsInvalidPriceLine(String field, Object value, String constraint) {
        var parameters = line(insert(header())).addValue(field, value);
        violation(() -> jdbc.update(load("insert_price_line_for_constraint_test"), parameters),
                "23514", constraint, "price_line");
    }

    /** FK nội bộ không cho dòng tiền mồ côi. */
    @Test
    void rejectsOrphanPriceLine() {
        violation(() -> jdbc.update(load("insert_price_line_for_constraint_test"), line(Long.MAX_VALUE)),
                "23503", "fk_booking_price_line_booking", "price_line");
    }

    /** Thứ tự dòng duy nhất và dòng đã lưu không sửa được. */
    @Test
    void rejectsDuplicatePriceLinePosition() {
        var parameters = line(insert(header()));
        jdbc.update(load("insert_price_line_for_constraint_test"), parameters);
        violation(() -> jdbc.update(load("insert_price_line_for_constraint_test"), parameters),
                "23505", "uq_booking_price_line_position", "price_line");
    }

    /** BR-122: thay riêng một dòng cũng không được lách sự bất biến của header. */
    @Test
    void savedPriceLineCannotBeUpdated() {
        long id = insert(header());
        jdbc.update(load("insert_price_line_for_constraint_test"), line(id));
        violation(() -> jdbc.update(load("update_price_line_for_constraint_test"), new MapSqlParameterSource("id", id)),
                "23514", "chk_booking_price_line_immutable", "price_line");
    }

    /** Lịch sử tạo phải có người thực hiện; sự kiện hệ thống không giả danh khách. */
    @ParameterizedTest
    @MethodSource("invalidHistory")
    void rejectsInvalidHistory(String field, Object value, String constraint) {
        var parameters = history(insert(header())).addValue(field, value, Types.VARCHAR);
        violation(() -> jdbc.update(load("insert_history_for_constraint_test"), parameters),
                "23514", constraint, "status_history");
    }

    /** Không sửa hoặc xoá sự kiện cũ, theo quy tắc lịch sử của status-flow §1. */
    @ParameterizedTest
    @ValueSource(strings = {"update_history_for_constraint_test", "delete_history_for_constraint_test"})
    void savedHistoryCannotBeChanged(String file) {
        long id = insert(header());
        jdbc.update(load("insert_history_for_constraint_test"), history(id));
        violation(() -> jdbc.update(load(file), new MapSqlParameterSource("id", id)),
                "23514", "chk_booking_history_immutable", "status_history");
    }

    /** Cùng một đích trạng thái không được ghi hai lần trong vòng đời của lát cắt này. */
    @Test
    void rejectsDuplicateHistory() {
        var parameters = history(insert(header()));
        jdbc.update(load("insert_history_for_constraint_test"), parameters);
        violation(() -> jdbc.update(load("insert_history_for_constraint_test"), parameters),
                "23505", "uq_booking_history_destination", "status_history");
    }

    /** Có thể chiếm key trước khi tạo đơn nhưng FK phải đúng trước commit, không để key mồ côi. */
    @Test
    void allowsKeyBeforeBookingInSameTransaction() {
        assertEquals(1, jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency()));
        insert(header());
        assertDoesNotThrow(() -> jdbc.getJdbcTemplate().execute(load("check_deferred_idempotency_fk")));
    }

    /** Nếu đơn không được tạo, FK hoãn cũng chặn khi hết thời gian hoãn kiểm tra. */
    @Test
    void rejectsUnfinishedIdempotencyClaim() {
        jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency());
        violation(() -> jdbc.getJdbcTemplate().execute(load("check_deferred_idempotency_fk")),
                "23503", "fk_booking_idempotency_booking", "idempotency");
    }

    /** Hai key cùng tên của cùng khách phải trúng UNIQUE, không tạo hai kết quả. */
    @Test
    void rejectsDuplicateCallerKey() {
        insert(header());
        jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency());
        violation(() -> jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency()),
                "23505", "uq_booking_idempotency_customer_key", "idempotency");
    }

    /** Hai khách có thể dùng cùng chuỗi key cho hai đơn khác nhau (api-guideline §5). */
    @Test
    void scopesIdempotencyKeyToCaller() {
        insert(header());
        insert(secondHeader().addValue("customer_account_code", "TK-OTHER1"));
        assertEquals(1, jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency()));
        assertEquals(1, jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency()
                .addValue("customer_account_code", "TK-OTHER1").addValue("booking_code", "DT-2611-TEST02")));
        jdbc.getJdbcTemplate().execute(load("check_deferred_idempotency_fk"));
    }

    /** Key rỗng, chủ sai và thời hạn không hợp lệ không được lưu để phát lại. */
    @ParameterizedTest
    @CsvSource({"idempotency_key,' ',chk_booking_idempotency_key",
            "customer_account_code,123,chk_booking_idempotency_customer",
            "quote_code,123,chk_booking_idempotency_quote",
            "expires_at,2026-11-10T03:00:00Z,chk_booking_idempotency_expiry",
            "expires_at,infinity,chk_booking_idempotency_expiry"})
    void rejectsInvalidIdempotencyRow(String field, String value, String constraint) {
        insert(header());
        violation(() -> jdbc.update(load("insert_idempotency_for_constraint_test"), idempotency().addValue(field, value)),
                "23514", constraint, "idempotency");
    }

    /** Không có FK từ booking sang pricing, identity, vehicle, branch hoặc availability. */
    @Test
    void hasNoCrossSchemaForeignKeys() {
        assertEquals(0L, jdbc.getJdbcTemplate().queryForObject(load("count_cross_schema_foreign_keys"), Long.class));
    }

    /** Chèn thật không ON CONFLICT để lỗi gốc không bị nuốt. */
    private long insert(MapSqlParameterSource parameters) {
        return jdbc.queryForObject(load("insert_booking_for_constraint_test"), parameters, Long.class);
    }

    /** Đơn khác mã/chỗ giữ, vẫn cùng chủ để kiểm partial unique đúng nguyên nhân. */
    private static MapSqlParameterSource secondHeader() {
        return header().addValue("code", "DT-2611-TEST02").addValue("reservation_code", "KL-TEST02");
    }

    /** Đọc SQL resource tĩnh; không lắp câu SQL từ dữ liệu đầu vào. */
    private String load(String name) {
        return sql.load("sql/booking/" + name + ".sql");
    }

    /** Chỉ chấp nhận đúng PostgreSQL SQLSTATE/schema/table/constraint. */
    private static void violation(Runnable operation, String state, String constraint, String table) {
        var failure = assertThrows(DataIntegrityViolationException.class, operation::run);
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals(state, cause.getSQLState());
        assertNotNull(cause.getServerErrorMessage());
        assertEquals("booking", cause.getServerErrorMessage().getSchema());
        assertEquals(table, cause.getServerErrorMessage().getTable());
        assertEquals(constraint, cause.getServerErrorMessage().getConstraint());
    }

    /** Các CHECK header được thử độc lập, kể cả NUMERIC NaN/Infinity. */
    private static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of("code", "DT-2613-TEST01", "chk_booking_code"),
                Arguments.of("customer_account_code", "TK-owner1", "chk_booking_customer"),
                Arguments.of("quote_code", "123", "chk_booking_quote"),
                Arguments.of("vehicle_code", "123", "chk_booking_vehicle"),
                Arguments.of("branch_code", "123", "chk_booking_branch"),
                Arguments.of("reservation_code", "123", "chk_booking_reservation"),
                Arguments.of("end_at", "2026-11-13T03:00:00Z", "chk_booking_period"),
                Arguments.of("start_at", "infinity", "chk_booking_period"),
                Arguments.of("rental_type", "MONTHLY", "chk_booking_rental_type"),
                Arguments.of("drive_mode", "WITH_DRIVER", "chk_booking_drive_mode"),
                Arguments.of("pickup_method", "DELIVERY", "chk_booking_pickup_method"),
                Arguments.of("daily_rate", 1500L, "chk_booking_daily_rate"),
                Arguments.of("insurance_value", "0", "chk_booking_insurance"),
                Arguments.of("insurance_value", "0.1", "chk_booking_insurance"),
                Arguments.of("insurance_value", "NaN", "chk_booking_insurance"),
                Arguments.of("insurance_calculation", "UNKNOWN", "chk_booking_insurance"),
                Arguments.of("insurance_billing_days", 0, "chk_booking_insurance"),
                Arguments.of("total_amount", 1_101_000L, "chk_booking_amounts"),
                Arguments.of("deposit_ratio", "NaN", "chk_booking_amounts"),
                Arguments.of("deposit_ratio", "0", "chk_booking_amounts"),
                Arguments.of("deposit_ratio", "1.1", "chk_booking_amounts"),
                Arguments.of("deposit", 300_000L, "chk_booking_amounts"),
                Arguments.of("balance", 769_000L, "chk_booking_amounts"),
                Arguments.of("turnaround_buffer_seconds", "0", "chk_booking_terms"),
                Arguments.of("turnaround_buffer_seconds", "NaN", "chk_booking_terms"),
                Arguments.of("turnaround_buffer_seconds", "0.0000000001", "chk_booking_terms"),
                Arguments.of("minimum_duration_seconds", "-1", "chk_booking_terms"),
                Arguments.of("branch_closes_at", "06:00:00", "chk_booking_terms"),
                Arguments.of("minimum_advance_seconds", "Infinity", "chk_booking_terms"),
                Arguments.of("maximum_advance_months", 0, "chk_booking_terms"),
                Arguments.of("status", "UNKNOWN", "chk_booking_status"),
                Arguments.of("hold_expires_at", "2026-11-10T03:00:00Z", "chk_booking_timestamps"),
                Arguments.of("created_at", "infinity", "chk_booking_timestamps"),
                Arguments.of("status_changed_at", "2026-11-09T03:00:00Z", "chk_booking_timestamps"));
    }

    /** Mỗi CHECK dòng có dữ liệu sai riêng. */
    private static Stream<Arguments> invalidLines() {
        return Stream.of(
                Arguments.of("line_number", 0, "chk_booking_price_line_position"),
                Arguments.of("type", "UNKNOWN", "chk_booking_price_line_type"),
                Arguments.of("hours", 23, "chk_booking_price_line_type"),
                Arguments.of("day_type", "UNKNOWN", "chk_booking_price_line_day"),
                Arguments.of("uplift", "0.2", "chk_booking_price_line_day"),
                Arguments.of("start_at", "infinity", "chk_booking_price_line_start"),
                Arguments.of("daily_rate", 1500, "chk_booking_price_line_rate"),
                Arguments.of("amount", 1500, "chk_booking_price_line_amount"));
    }

    /** Chặn lịch sử thiếu actor, thiếu trạng thái trước hoặc giả mạo loại sự kiện. */
    private static Stream<Arguments> invalidHistory() {
        return Stream.of(
                Arguments.of("actor_account_code", null, "chk_booking_history_transition"),
                Arguments.of("actor_account_code", "123", "chk_booking_history_actor"),
                Arguments.of("to_status", "CANCELLED", "chk_booking_history_transition"),
                Arguments.of("reason", "UNKNOWN", "chk_booking_history_transition"),
                Arguments.of("occurred_at", "infinity", "chk_booking_history_timestamp"));
    }
}
