package com.carrental.availability.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm cổng ghi và SQL thật bằng PostgreSQL theo BR-103, BR-104, BR-015 và ADR-0005.
 *
 * <p>Chỉ ghi qua WriteReservationPort; SQL test chỉ dùng đọc để đối chiếu độc lập.
 * Dùng database riêng của Testcontainers và rollback dữ liệu từng test.
 * Chưa thay thế phép thử 50 luồng qua hold() ở bước application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ReservationWriteAdapterIntegrationTest {

    private static final String CODE = "adapter-integration-first";
    private static final String OTHER_CODE = "adapter-integration-second";
    private static final long VEHICLE_ID = 9_000_000_000_101L;
    private static final Instant CREATED = OffsetDateTime.parse("2000-01-01T08:02:03.123456+07:00").toInstant();
    private static final Instant START = OffsetDateTime.parse("2030-09-30T10:00:00+07:00").toInstant();
    private static final Instant END = START.plus(Duration.ofHours(2));

    @Autowired
    private WriteReservationPort writePort;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private SqlLoader sqlLoader;

    private String readSql;

    /** Tải lại câu đọc độc lập đã có, không tạo thêm SQL ghi phục vụ test. */
    @BeforeEach
    void loadReadSql() {
        readSql = sqlLoader.load("sql/availability/read_reservation_for_constraint_test.sql");
    }

    /**
     * Kiểm mọi trạng thái RENTAL hợp lệ và các mốc giờ được lưu nguyên, không ép lại HELD.
     *
     * @param status trạng thái hợp lệ cần lưu
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "BLOCKED", mode = EnumSource.Mode.EXCLUDE)
    void preservesRentalFieldsAndStatus(ReservationStatus status) {
        Reservation reservation = Reservation.restore(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                ReservationKind.RENTAL, status, "adapter-booking", null, CREATED.plus(Duration.ofMinutes(90)),
                CREATED, CREATED.plusSeconds(17));
        assertStored(reservation, writePort.insert(reservation).orElseThrow());
    }

    /**
     * Kiểm năm loại khóa vận hành giữ đúng lý do, mã đơn null và TTL null.
     *
     * @param kind nguyên nhân khóa vận hành
     */
    @ParameterizedTest
    @EnumSource(value = ReservationKind.class, names = "RENTAL", mode = EnumSource.Mode.EXCLUDE)
    void preservesOperationalFields(ReservationKind kind) {
        Reservation reservation = Reservation.createBlocked(CODE, VEHICLE_ID,
                ReservationPeriod.finite(START, END), kind, "  Scheduled work  ", CREATED);
        assertStored(reservation, writePort.insert(reservation).orElseThrow());
    }

    /** Kiểm cận trên null thực sự thành upper_inf, không phải một ngày giả trong tương lai. */
    @Test
    void preservesUnboundedComplianceAndNullReason() {
        Reservation reservation = compliance();
        assertStored(reservation, writePort.insert(reservation).orElseThrow());
    }

    /** Kiểm chỉ trùng mã thì trả rỗng, giữ bản gốc và vẫn ghi tiếp được cùng transaction. */
    @Test
    void skipsDuplicateCodeWithoutOverwriteOrTransactionFailure() {
        Reservation original = held(CODE, VEHICLE_ID, START, END);
        long originalId = writePort.insert(original).orElseThrow();
        Reservation duplicate = Reservation.createBlocked(CODE, VEHICLE_ID + 1,
                ReservationPeriod.finite(START, END), ReservationKind.MAINTENANCE, "Other data", CREATED);
        assertTrue(writePort.insert(duplicate).isEmpty());
        assertStored(original, originalId);
        Reservation next = held(OTHER_CODE, VEHICLE_ID + 1, START, END);
        assertStored(next, writePort.insert(next).orElseThrow());
    }

    /**
     * Kiểm các trạng thái đang chặn đều gây đúng lỗi trùng lịch, kể cả HELD quá hạn chưa dọn.
     *
     * @param status trạng thái bản ghi đã chiếm lịch
     */
    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RELEASED", mode = EnumSource.Mode.EXCLUDE)
    void translatesOverlapWithBlockingStatus(ReservationStatus status) {
        Reservation existing = status == ReservationStatus.BLOCKED
                ? Reservation.createBlocked(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                        ReservationKind.MAINTENANCE, null, CREATED)
                : Reservation.restore(CODE, VEHICLE_ID, ReservationPeriod.finite(START, END),
                        ReservationKind.RENTAL, status, "adapter-booking", null,
                        CREATED.plusSeconds(3600), CREATED, CREATED);
        writePort.insert(existing).orElseThrow();
        assertOverlap(held(OTHER_CODE, VEHICLE_ID, START.plusSeconds(1800), END.plusSeconds(1800)));
    }

    /** Kiểm hai khoảng liền kề trên cùng xe, cùng đơn vẫn được chèn với ID riêng. */
    @Test
    void acceptsAdjacentRangesForSameBooking() {
        Reservation first = held(CODE, VEHICLE_ID, START, END);
        Reservation second = held(OTHER_CODE, VEHICLE_ID, END, END.plusSeconds(7200));
        long firstId = writePort.insert(first).orElseThrow();
        long secondId = writePort.insert(second).orElseThrow();
        assertNotEquals(firstId, secondId);
        assertStored(first, firstId);
        assertStored(second, secondId);
    }

    /** Kiểm cùng khoảng trên hai xe khác nhau không gây xung đột. */
    @Test
    void acceptsSameRangeOnDifferentVehicles() {
        Reservation first = held(CODE, VEHICLE_ID, START, END);
        Reservation second = held(OTHER_CODE, VEHICLE_ID + 1, START, END);
        assertStored(first, writePort.insert(first).orElseThrow());
        assertStored(second, writePort.insert(second).orElseThrow());
    }

    /** Kiểm bản ghi RELEASED không còn cản việc chèn trên cùng khoảng. */
    @Test
    void acceptsRangeOverReleasedReservation() {
        Reservation released = held(CODE, VEHICLE_ID, START, END).release(CREATED.plusSeconds(10));
        writePort.insert(released).orElseThrow();
        Reservation next = held(OTHER_CODE, VEHICLE_ID, START, END);
        assertStored(next, writePort.insert(next).orElseThrow());
    }

    /**
     * Kiểm khoảng không chặn trên chặn cả ngay mốc đầu và lịch rất xa.
     *
     * @param days khoảng cách từ mốc bắt đầu compliance
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 3650})
    void unboundedComplianceRejectsLaterRanges(long days) {
        writePort.insert(compliance()).orElseThrow();
        Instant start = START.plus(Duration.ofDays(days));
        assertOverlap(held(OTHER_CODE, VEHICLE_ID, start, start.plusSeconds(7200)));
    }

    /** Kiểm adapter lưu đúng khoảng đã cộng đệm, không tự cộng thêm đệm lần nữa. */
    @Test
    void preservesBufferWithoutAddingItTwice() {
        Reservation first = held(CODE, VEHICLE_ID, START, END.plusSeconds(7200));
        assertStored(first, writePort.insert(first).orElseThrow());
        Reservation next = held(OTHER_CODE, VEHICLE_ID, END.plusSeconds(7200), END.plusSeconds(14400));
        assertStored(next, writePort.insert(next).orElseThrow());
    }

    /** Kiểm lượt sau nằm trong phần đệm bị từ chối qua chính SQL của adapter. */
    @Test
    void rejectsRangeInsideStoredBuffer() {
        writePort.insert(held(CODE, VEHICLE_ID, START, END.plusSeconds(7200))).orElseThrow();
        assertOverlap(held(OTHER_CODE, VEHICLE_ID, END.plusSeconds(1800), END.plusSeconds(9000)));
    }

    /** Kiểm ID đã trả về không có nghĩa đã commit: rollback bên gọi xóa toàn bộ lần chèn. */
    @Test
    void joinsCallerTransactionAndRollsBackInsert() {
        Reservation reservation = held(CODE, VEHICLE_ID, START, END);
        assertStored(reservation, writePort.insert(reservation).orElseThrow());
        rollbackAndStartFreshTransaction();
        assertTrue(jdbc.queryForList(readSql, Map.of("code", CODE)).isEmpty());
    }

    /** Kiểm rollback sau lỗi trùng lịch cũng hủy thao tác chèn trước đó trong cùng transaction. */
    @Test
    void rollsBackEarlierWriteAfterOverlap() {
        writePort.insert(held(CODE, VEHICLE_ID, START, END)).orElseThrow();
        assertOverlap(held(OTHER_CODE, VEHICLE_ID, START, END));
        rollbackAndStartFreshTransaction();
        assertTrue(jdbc.queryForList(readSql, Map.of("code", CODE)).isEmpty());
        assertTrue(jdbc.queryForList(readSql, Map.of("code", OTHER_CODE)).isEmpty());
    }

    /** Tạo dữ liệu giữ chỗ cố định; mã chỉ là dữ liệu test, không quyết định mẫu mã nghiệp vụ. */
    private static Reservation held(String code, long vehicleId, Instant start, Instant end) {
        return Reservation.createHeld(code, vehicleId, ReservationPeriod.finite(start, end),
                "adapter-booking", Duration.ofMinutes(90), CREATED);
    }

    /** Tạo khóa do giấy tờ không có cận trên và không có lý do bổ sung. */
    private static Reservation compliance() {
        return Reservation.createBlocked(CODE, VEHICLE_ID, ReservationPeriod.unboundedFrom(START),
                ReservationKind.COMPLIANCE_HOLD, null, CREATED);
    }

    /** Đối chiếu đầy đủ dữ liệu đã lưu bằng SQL đọc độc lập với adapter ghi. */
    private void assertStored(Reservation expected, long expectedId) {
        List<Long> ids = jdbc.query(readSql, Map.of("code", expected.code()), (rs, rowNum) -> {
            assertEquals(expectedId, rs.getLong("id"));
            assertEquals(expected.code(), rs.getString("code"));
            assertEquals(expected.vehicleId(), rs.getLong("vehicle_id"));
            assertEquals(expected.kind().name(), rs.getString("kind"));
            assertEquals(expected.status().name(), rs.getString("status"));
            assertEquals(expected.bookingCode(), rs.getString("booking_code"));
            assertEquals(expected.reason(), rs.getString("reason"));
            assertEquals(expected.period().startInclusive(), readInstant(rs, "starts_at"));
            assertEquals(expected.period().endExclusive(), readInstant(rs, "ends_at"));
            assertTrue(rs.getBoolean("start_inclusive"));
            assertFalse(rs.getBoolean("end_inclusive"));
            assertEquals(expected.period().isUnbounded(), rs.getBoolean("end_unbounded"));
            assertEquals(expected.holdExpiresAt(), readInstant(rs, "hold_expires_at"));
            assertEquals(expected.createdAt(), readInstant(rs, "created_at"));
            assertEquals(expected.statusChangedAt(), readInstant(rs, "status_changed_at"));
            return rs.getLong("id");
        });
        assertTrue(expectedId > 0);
        assertEquals(List.of(expectedId), ids);
    }

    /** Kiểm lỗi trùng lịch thật giữ đủ SQLSTATE, schema, bảng, constraint và cause JDBC. */
    private void assertOverlap(Reservation requested) {
        ReservationOverlapException failure = assertThrows(ReservationOverlapException.class,
                () -> writePort.insert(requested));
        DataIntegrityViolationException jdbcFailure = assertInstanceOf(DataIntegrityViolationException.class,
                failure.getCause());
        PSQLException postgres = assertInstanceOf(PSQLException.class, jdbcFailure.getMostSpecificCause());
        assertEquals("23P01", postgres.getSQLState());
        ServerErrorMessage details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("availability", details.getSchema());
        assertEquals("reservation", details.getTable());
        assertEquals("reservation_no_overlap", details.getConstraint());
    }

    /** Đọc timestamptz thành Instant để so thời điểm, không phụ thuộc múi giờ hiển thị. */
    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Rollback transaction đang có và mở transaction mới để kiểm dữ liệu từ phía bên ngoài. */
    private static void rollbackAndStartFreshTransaction() {
        TestTransaction.flagForRollback();
        TestTransaction.end();
        TestTransaction.start();
    }
}
