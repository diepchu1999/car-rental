package com.carrental.availability.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm DDL V004 và V005 bằng PostgreSQL thật theo ADR-0005, BR-103, BR-104,
 * BR-015, BR-109, BR-116 và database-guideline §4.
 *
 * <p>Ghi trực tiếp bằng JDBC để bỏ qua bảo vệ của domain.
 * Dùng PostgresTestConfiguration, không đụng database local hoặc tạo xe thật.
 * Mỗi test rollback riêng; sau lỗi SQL chỉ kiểm exception vì transaction đã lỗi.
 * Đây chưa phải test đồng thời hoặc test use case hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationConstraintIntegrationTest {

    private static final String SQL_ROOT = "sql/availability/";
    private static final String CODE = "reservation-db-test-1";
    private static final String OTHER_CODE = "reservation-db-test-2";
    private static final String BOOKING_CODE = "booking-db-test-1";
    private static final long VEHICLE_ID = 9_000_000_000_001L;
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2000-01-01T08:00:00Z");
    private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2000-01-01T09:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2030-09-30T10:00:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2030-09-30T12:00:00Z");

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private SqlLoader sqlLoader;

    private String insertSql;
    private String readSql;
    private String updateSql;

    /** Tải SQL của test, tách biệt với SQL nghiệp vụ sẽ được viết ở bước sau. */
    @BeforeEach
    void loadSql() {
        insertSql = sqlLoader.load(SQL_ROOT + "insert_reservation_for_constraint_test.sql");
        readSql = sqlLoader.load(SQL_ROOT + "read_reservation_for_constraint_test.sql");
        updateSql = sqlLoader.load(SQL_ROOT + "update_reservation_status_for_constraint_test.sql");
    }

    /** Chứng minh Flyway áp dụng cả V004 và V005 bằng car_rental_app không phải superuser. */
    @ParameterizedTest
    @CsvSource({"4, V004__availability_tables.sql", "5, V005__availability_shape_checks.sql"})
    void appliesMigrationUsingApplicationRole(int version, String script) {
        Map<String, Object> row = jdbc.queryForMap(
                sqlLoader.load(SQL_ROOT + "read_migration_for_constraint_test.sql"), Map.of("script", script));
        assertEquals(version, Integer.parseInt((String) row.get("version")));
        assertEquals(true, row.get("success"));
        assertEquals("car_rental_app", row.get("installed_by"));
        assertEquals("car_rental_app", row.get("database_user"));
        assertEquals(false, row.get("superuser"));
    }

    /** Kiểm đủ index GIST xe/khoảng và index hạn chỉ dành cho HELD. */
    @Test
    void createsRequiredIndexes() {
        Map<String, String> indexes = jdbc.query(
                sqlLoader.load(SQL_ROOT + "read_indexes_for_constraint_test.sql"), Map.of(),
                resultSet -> {
                    Map<String, String> result = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        result.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
                    }
                    return result;
                });
        assertNotNull(indexes);
        String periodIndex = indexes.get("idx_reservation_vehicle_period");
        String expiryIndex = indexes.get("idx_reservation_hold_expiry");
        assertNotNull(periodIndex);
        assertNotNull(expiryIndex);
        assertTrue(periodIndex.contains("USING gist (vehicle_id, period)"));
        assertTrue(expiryIndex.contains("USING btree (hold_expires_at)"));
        assertTrue(expiryIndex.contains("WHERE (status = 'HELD'::text)"));
    }

    /** Kiểm identity ALWAYS và hai mốc Clock bắt buộc, không có DEFAULT now(). */
    @Test
    void requiresApplicationClockValuesAndGeneratesIdentity() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                sqlLoader.load(SQL_ROOT + "read_columns_for_constraint_test.sql"), Map.of());
        assertEquals(3, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("NO", row.get("is_nullable"));
            if ("id".equals(row.get("column_name"))) {
                assertEquals("ALWAYS", row.get("identity_generation"));
            } else {
                assertNull(row.get("column_default"));
            }
        }
    }

    /** Kiểm không có khóa ngoại xuyên module và có thể lưu ID xe chưa tồn tại. */
    @Test
    void acceptsLogicalVehicleIdWithoutCrossModuleForeignKeys() {
        Long count = jdbc.queryForObject(
                sqlLoader.load(SQL_ROOT + "count_foreign_keys_for_constraint_test.sql"),
                Map.of(), Long.class);
        assertEquals(0L, count);
        insert(heldRow(CODE));
    }

    /**
     * Kiểm cả năm loại khóa vận hành được lưu, không cần mã đơn hoặc TTL.
     *
     * @param kind loại khóa vận hành hợp lệ
     */
    @ParameterizedTest
    @ValueSource(strings = {"MAINTENANCE", "INSPECTION", "TRANSFER", "OWNER_BLOCK", "COMPLIANCE_HOLD"})
    void acceptsOperationalKinds(String kind) {
        MapSqlParameterSource row = blockedRow(CODE, kind);
        if ("COMPLIANCE_HOLD".equals(kind)) {
            row.addValue("end", null);
        }
        insert(row);
    }

    /** Kiểm đủ các cặp loại/trạng thái hợp lệ, không vô tình cấm lịch sử COMPLETED hoặc RELEASED của đơn thuê. */
    @ParameterizedTest
    @MethodSource("validKindStatuses")
    void acceptsValidKindStatusPairs(String kind, String status) {
        MapSqlParameterSource row = "RENTAL".equals(kind) ? heldRow(CODE) : blockedRow(CODE, kind);
        row.addValue("status", status);
        if ("COMPLIANCE_HOLD".equals(kind)) {
            row.addValue("end", null);
        }
        insert(row);
    }

    /** Kiểm MAINTENANCE/HELD bị đúng CHECK cặp loại/trạng thái chặn dù TTL hoàn toàn hợp lệ. */
    @Test
    void rejectsHeldMaintenance() {
        assertViolation(() -> insert(blockedRow(CODE, "MAINTENANCE")
                        .addValue("status", "HELD").addValue("holdExpiresAt", EXPIRY)),
                "23514", "chk_kind_status");
    }

    /** Kiểm RENTAL/BLOCKED bị đúng CHECK cặp loại/trạng thái chặn dù mã đơn có mặt. */
    @Test
    void rejectsBlockedRental() {
        assertViolation(() -> insert(heldRow(CODE).addValue("status", "BLOCKED")),
                "23514", "chk_kind_status");
    }

    /** Kiểm ghi SQL trực tiếp cũng không tạo được khóa giấy tờ hữu hạn theo BR-015. */
    @Test
    void rejectsFiniteComplianceHold() {
        assertViolation(() -> insert(blockedRow(CODE, "COMPLIANCE_HOLD")),
                "23514", "chk_compliance_open_blocked");
    }

    /** Kiểm UPDATE trực tiếp không thể hoàn tất khóa giấy tờ đang BLOCKED và không chặn trên. */
    @Test
    void rejectsCompletingComplianceHold() {
        insert(blockedRow(CODE, "COMPLIANCE_HOLD").addValue("end", null));
        assertViolation(() -> updateStatus(CODE, "COMPLETED", CREATED.plusMinutes(1)),
                "23514", "chk_compliance_open_blocked");
    }

    /**
     * Kiểm DDL ép tập giá trị kind, không chỉ dựa vào comment hoặc enum Java.
     *
     * @param kind giá trị sai cần thử
     */
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "rental"})
    void rejectsUnknownKind(String kind) {
        // BLOCKED hợp lệ với nhánh kind <> RENTAL, nên chỉ danh mục kind bị vi phạm.
        assertViolation(() -> insert(blockedRow(CODE, kind)),
                "23514", "chk_reservation_kind");
    }

    /**
     * Kiểm trạng thái sai bị chặn trước khi có thể lọt khỏi exclusion predicate.
     *
     * @param status giá trị sai cần thử
     */
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "held"})
    void rejectsUnknownStatus(String status) {
        // Trạng thái ngoài danh mục cũng vi phạm cặp kind/status; CHECK này được kiểm trước theo tên.
        assertViolation(() -> insert(heldRow(CODE).addValue("status", status)),
                "23514", "chk_kind_status");
    }

    /**
     * Kiểm khoảng rỗng, thiếu cận dưới hoặc sai dấu cận đều bị đúng CHECK chặn.
     *
     * @param start cận dưới, có thể null để cố ý vi phạm
     * @param end cận trên
     * @param bounds hình dạng cận truyền cho PostgreSQL
     */
    @ParameterizedTest
    @MethodSource("invalidPeriodShapes")
    void rejectsInvalidPeriodShape(OffsetDateTime start, OffsetDateTime end, String bounds) {
        MapSqlParameterSource row = heldRow(CODE)
                .addValue("start", start).addValue("end", end).addValue("bounds", bounds);
        assertViolation(() -> insert(row), "23514", "chk_period_shape");
    }

    /** Kiểm COMPLIANCE_HOLD không chặn trên được lưu và đọc đúng cận. */
    @Test
    void acceptsUnboundedComplianceHold() {
        insert(blockedRow(CODE, "COMPLIANCE_HOLD").addValue("end", null));
        List<String> codes = jdbc.query(readSql, Map.of("code", CODE), (rs, rowNum) -> {
            assertEquals(START.toInstant(), rs.getObject("starts_at", OffsetDateTime.class).toInstant());
            assertNull(rs.getObject("ends_at", OffsetDateTime.class));
            assertTrue(rs.getBoolean("end_unbounded"));
            assertTrue(rs.getBoolean("start_inclusive"));
            assertFalse(rs.getBoolean("end_inclusive"));
            return rs.getString("code");
        });
        assertEquals(List.of(CODE), codes);
    }

    /**
     * Kiểm mọi loại khác không được tạo khoảng vô hạn về phía trên.
     *
     * @param kind loại không được dùng cận trên vô hạn
     */
    @ParameterizedTest
    @ValueSource(strings = {"RENTAL", "MAINTENANCE", "INSPECTION", "TRANSFER", "OWNER_BLOCK"})
    void rejectsUnboundedPeriodForOtherKinds(String kind) {
        MapSqlParameterSource row = "RENTAL".equals(kind) ? heldRow(CODE) : blockedRow(CODE, kind);
        row.addValue("end", null);
        assertViolation(() -> insert(row), "23514", "chk_unbounded_only_compliance");
    }

    /** Kiểm INSERT thẳng HELD thiếu hạn bị đúng chk_hold_has_ttl chặn. */
    @Test
    void rejectsHeldWithoutExpiry() {
        assertViolation(() -> insert(heldRow(CODE).addValue("holdExpiresAt", null)),
                "23514", "chk_hold_has_ttl");
    }

    /**
     * Kiểm có hạn nhưng hạn bằng hoặc trước lúc tạo cũng không hợp lệ.
     *
     * @param seconds độ lệch hạn so với lúc tạo
     */
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsExpiryNotAfterCreation(long seconds) {
        assertViolation(() -> insert(heldRow(CODE).addValue("holdExpiresAt", CREATED.plusSeconds(seconds))),
                "23514", "chk_hold_has_ttl");
    }

    /**
     * Kiểm CSDL không đóng cứng một giờ; độ dài chính sách thuộc application.
     *
     * @param minutes độ dài hợp lệ về cấu trúc
     */
    @ParameterizedTest
    @ValueSource(longs = {60, 90})
    void acceptsPositiveExpiryWithoutHardcodedPolicyLength(long minutes) {
        insert(heldRow(CODE).addValue("holdExpiresAt", CREATED.plusMinutes(minutes)));
    }

    /** Kiểm RENTAL thiếu mã đơn bị đúng chk_rental_has_code chặn. */
    @Test
    void rejectsRentalWithoutBookingCode() {
        assertViolation(() -> insert(heldRow(CODE).addValue("bookingCode", null)),
                "23514", "chk_rental_has_code");
    }

    /** Kiểm mã reservation duy nhất, tách khỏi lỗi chồng lịch bằng cách dùng xe khác. */
    @Test
    void rejectsDuplicateReservationCode() {
        insert(heldRow(CODE));
        assertViolation(() -> insert(heldRow(CODE).addValue("vehicleId", VEHICLE_ID + 1)),
                "23505", "uq_reservation_code");
    }

    /** Kiểm cùng mã đơn được có nhiều reservation liền kề để hỗ trợ gia hạn BR-427. */
    @Test
    void acceptsMultipleReservationsForOneBooking() {
        long firstId = insert(heldRow(CODE));
        long secondId = insert(heldRow(OTHER_CODE).addValue("start", END).addValue("end", END.plusHours(2)));
        assertTrue(firstId != secondId);
    }

    /**
     * Kiểm hai mốc Clock bị bỏ trống sẽ lỗi NOT NULL, không được tự điền giờ CSDL.
     *
     * @param parameter tên tham số bị bỏ trống
     * @param column tên cột phải xuất hiện trong lỗi PostgreSQL
     */
    @ParameterizedTest
    @CsvSource({"createdAt, created_at", "statusChangedAt, status_changed_at"})
    void rejectsMissingClockTimestamp(String parameter, String column) {
        ServerErrorMessage error = assertViolation(() -> insert(heldRow(CODE).addValue(parameter, null)),
                "23502", null);
        assertEquals(column, error.getColumn());
    }

    /** Kiểm lý do và các mốc thời gian round-trip qua cột thật, không mất khoảng trắng. */
    @Test
    void preservesReasonRangeAndClockValues() {
        String reason = "  Scheduled maintenance  ";
        OffsetDateTime changed = CREATED.plusMinutes(10);
        long id = insert(blockedRow(CODE, "MAINTENANCE")
                .addValue("reason", reason).addValue("statusChangedAt", changed));
        List<String> codes = jdbc.query(readSql, Map.of("code", CODE), (rs, rowNum) -> {
            assertEquals(id, rs.getLong("id"));
            assertEquals(VEHICLE_ID, rs.getLong("vehicle_id"));
            assertEquals("MAINTENANCE", rs.getString("kind"));
            assertEquals("BLOCKED", rs.getString("status"));
            assertNull(rs.getString("booking_code"));
            assertNull(rs.getObject("hold_expires_at"));
            assertEquals(reason, rs.getString("reason"));
            assertEquals(START.toInstant(), rs.getObject("starts_at", OffsetDateTime.class).toInstant());
            assertEquals(END.toInstant(), rs.getObject("ends_at", OffsetDateTime.class).toInstant());
            assertEquals(CREATED.toInstant(), rs.getObject("created_at", OffsetDateTime.class).toInstant());
            assertEquals(changed.toInstant(), rs.getObject("status_changed_at", OffsetDateTime.class).toInstant());
            assertTrue(rs.getBoolean("start_inclusive"));
            assertFalse(rs.getBoolean("end_inclusive"));
            assertFalse(rs.getBoolean("end_unbounded"));
            return rs.getString("code");
        });
        assertEquals(List.of(CODE), codes);
    }

    /**
     * Kiểm đủ năm trạng thái đang chặn, gồm HELD có hạn cũ và COMPLETED.
     *
     * @param status trạng thái bản ghi đã chiếm lịch
     */
    @ParameterizedTest
    @ValueSource(strings = {"HELD", "CONFIRMED", "IN_USE", "BLOCKED", "COMPLETED"})
    void rejectsOverlapWithEveryBlockingStatus(String status) {
        MapSqlParameterSource existing = "BLOCKED".equals(status)
                ? blockedRow(CODE, "MAINTENANCE") : heldRow(CODE).addValue("status", status);
        insert(existing);
        assertViolation(() -> insert(heldRow(OTHER_CODE)), "23P01", "reservation_no_overlap");
    }

    /**
     * Kiểm chồng một phần hai phía, bao trùm và nằm trọn trong lịch CONFIRMED.
     *
     * @param startMinutes độ lệch đầu so với 10 giờ
     * @param endMinutes độ lệch cuối so với 10 giờ
     */
    @ParameterizedTest
    @CsvSource({"-60, 60", "60, 180", "-60, 180", "30, 90"})
    void rejectsPartialAndContainedOverlap(long startMinutes, long endMinutes) {
        insert(heldRow(CODE).addValue("status", "CONFIRMED"));
        MapSqlParameterSource requested = heldRow(OTHER_CODE)
                .addValue("start", START.plusMinutes(startMinutes))
                .addValue("end", START.plusMinutes(endMinutes));
        assertViolation(() -> insert(requested), "23P01", "reservation_no_overlap");
    }

    /** Kiểm [10,12) và [12,14) đều được chấp nhận với hai mã đơn khác nhau. */
    @Test
    void acceptsAdjacentHalfOpenPeriods() {
        insert(heldRow(CODE));
        insert(heldRow(OTHER_CODE).addValue("bookingCode", "booking-db-test-2")
                .addValue("start", END).addValue("end", END.plusHours(2)));
    }

    /** Kiểm hai xe khác nhau được có cùng khoảng, không bị khóa lịch toàn hệ thống. */
    @Test
    void acceptsSamePeriodOnDifferentVehicles() {
        insert(heldRow(CODE));
        insert(heldRow(OTHER_CODE).addValue("vehicleId", VEHICLE_ID + 1));
    }

    /** Kiểm RELEASED không chặn giữ chỗ mới trên chính khoảng cũ. */
    @Test
    void acceptsOverlapWithReleasedReservation() {
        insert(heldRow(CODE).addValue("status", "RELEASED"));
        insert(heldRow(OTHER_CODE));
    }

    /**
     * Kiểm khóa compliance chặn ngay mốc bắt đầu và cả lịch xa trong tương lai.
     *
     * @param days khoảng cách từ mốc bắt đầu khóa
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 3650})
    void unboundedComplianceBlocksLaterReservations(long days) {
        insert(blockedRow(CODE, "COMPLIANCE_HOLD").addValue("end", null));
        MapSqlParameterSource requested = heldRow(OTHER_CODE)
                .addValue("start", START.plusDays(days)).addValue("end", START.plusDays(days).plusHours(2));
        assertViolation(() -> insert(requested), "23P01", "reservation_no_overlap");
    }

    /** Kiểm khóa compliance không chặn nhầm lượt kết thúc đúng lúc khóa bắt đầu. */
    @Test
    void acceptsPeriodEndingAtComplianceStart() {
        insert(blockedRow(CODE, "COMPLIANCE_HOLD").addValue("end", null));
        insert(heldRow(OTHER_CODE).addValue("start", START.minusHours(2)).addValue("end", START));
    }

    /** Kiểm UPDATE hoàn tất lúc 12 giờ vẫn chặn lượt 12 giờ 30 trong phần đệm tới 14 giờ. */
    @Test
    void completingReservationPreservesBufferProtection() {
        insert(heldRow(CODE).addValue("status", "IN_USE").addValue("end", END.plusHours(2)));
        assertEquals(1, updateStatus(CODE, "COMPLETED", END));
        assertViolation(() -> insert(heldRow(OTHER_CODE)
                        .addValue("start", END.plusMinutes(30)).addValue("end", END.plusHours(3))),
                "23P01", "reservation_no_overlap");
    }

    /** Kiểm COMPLETED không chặn lượt bắt đầu đúng lúc hết phần đệm. */
    @Test
    void completedReservationAllowsNextPeriodAfterBuffer() {
        insert(heldRow(CODE).addValue("status", "COMPLETED").addValue("end", END.plusHours(2)));
        insert(heldRow(OTHER_CODE).addValue("start", END.plusHours(2)).addValue("end", END.plusHours(4)));
    }

    /** Kiểm UPDATE sang RELEASED thực sự giải phóng khoảng cho bản ghi mới. */
    @Test
    void releasingReservationAllowsNewHold() {
        insert(heldRow(CODE));
        assertEquals(1, updateStatus(CODE, "RELEASED", EXPIRY));
        insert(heldRow(OTHER_CODE));
    }

    /** Kiểm exclusion constraint bảo vệ cả UPDATE, không chỉ INSERT. */
    @Test
    void rejectsUpdateThatReintroducesOverlap() {
        insert(heldRow(CODE));
        insert(heldRow(OTHER_CODE).addValue("status", "RELEASED"));
        assertViolation(() -> updateStatus(OTHER_CODE, "CONFIRMED", EXPIRY),
                "23P01", "reservation_no_overlap");
    }

    /**
     * Tạo bộ tham số HELD hợp lệ để mỗi test chỉ thay thuộc tính đang kiểm.
     *
     * @param code mã riêng của bản ghi test, không quy định mẫu mã nghiệp vụ
     * @return bộ tham số có kiểu SQL rõ ràng cho cả giá trị null
     */
    private static MapSqlParameterSource heldRow(String code) {
        return new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("vehicleId", VEHICLE_ID, Types.BIGINT)
                .addValue("start", START, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("end", END, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("bounds", "[)", Types.VARCHAR)
                .addValue("kind", "RENTAL", Types.VARCHAR)
                .addValue("status", "HELD", Types.VARCHAR)
                .addValue("bookingCode", BOOKING_CODE, Types.VARCHAR)
                .addValue("reason", null, Types.VARCHAR)
                .addValue("holdExpiresAt", EXPIRY, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("createdAt", CREATED, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("statusChangedAt", CREATED, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /**
     * Tạo dữ liệu khóa vận hành không có mã đơn hoặc hạn giữ chỗ.
     *
     * @param code mã bản ghi test
     * @param kind nguyên nhân khóa vận hành
     * @return bộ tham số BLOCKED hợp lệ
     */
    private static MapSqlParameterSource blockedRow(String code, String kind) {
        return heldRow(code).addValue("kind", kind).addValue("status", "BLOCKED")
                .addValue("bookingCode", null).addValue("holdExpiresAt", null);
    }

    /**
     * Ghi dữ liệu thẳng xuống PostgreSQL và kiểm database sinh ID dương.
     *
     * @param row bộ tham số cần ghi
     * @return ID do PostgreSQL sinh
     */
    private long insert(MapSqlParameterSource row) {
        Long id = jdbc.queryForObject(insertSql, row, Long.class);
        assertNotNull(id);
        assertTrue(id > 0);
        return id;
    }

    /**
     * Cập nhật trạng thái trực tiếp để thử ràng buộc, không thay thế use case.
     *
     * @param code mã bản ghi test
     * @param status trạng thái cần thử
     * @param changedAt mốc thay đổi cố định
     * @return số dòng tác động
     */
    private int updateStatus(String code, String status, OffsetDateTime changedAt) {
        return jdbc.update(updateSql, new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR).addValue("status", status, Types.VARCHAR)
                .addValue("changedAt", changedAt, Types.TIMESTAMP_WITH_TIMEZONE));
    }

    /** Cung cấp đủ cặp hợp lệ của đơn thuê, bốn loại vận hành hữu hạn và khóa giấy tờ theo status-flow §2. */
    private static Stream<Arguments> validKindStatuses() {
        Stream<Arguments> rental = Stream.of("HELD", "CONFIRMED", "IN_USE", "RELEASED", "COMPLETED")
                .map(status -> Arguments.of("RENTAL", status));
        Stream<Arguments> operational = Stream.of("MAINTENANCE", "INSPECTION", "TRANSFER", "OWNER_BLOCK")
                .flatMap(kind -> Stream.of("BLOCKED", "COMPLETED").map(status -> Arguments.of(kind, status)));
        return Stream.concat(Stream.concat(rental, operational),
                Stream.of(Arguments.of("COMPLIANCE_HOLD", "BLOCKED")));
    }

    /**
     * Cung cấp khoảng sai do hình dạng, không gây lỗi cú pháp hoặc chồng lịch.
     *
     * @return các trường hợp chỉ vi phạm chk_period_shape
     */
    private static Stream<Arguments> invalidPeriodShapes() {
        return Stream.of(Arguments.of(START, START, "[)"),
                Arguments.of(START, END, "()"), Arguments.of(START, END, "[]"),
                Arguments.of(START, END, "(]"), Arguments.of(null, END, "[)"));
    }

    /**
     * Kiểm lỗi PostgreSQL bằng SQLSTATE và thông tin ràng buộc có cấu trúc.
     *
     * @param action thao tác phải bị chặn
     * @param sqlState mã SQLSTATE mong đợi
     * @param constraint tên constraint; null với NOT NULL
     * @return chi tiết lỗi để kiểm tên cột nếu cần
     */
    private static ServerErrorMessage assertViolation(Executable action, String sqlState, String constraint) {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class, action);
        PSQLException postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals(sqlState, postgres.getSQLState());
        ServerErrorMessage details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("availability", details.getSchema());
        assertEquals("reservation", details.getTable());
        assertEquals(constraint, details.getConstraint());
        return details;
    }
}
