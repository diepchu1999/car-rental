package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Kiểm hợp đồng JDBC và phân loại lỗi của adapter theo BR-104, ADR-0005.
 *
 * <p>Mô phỏng JDBC chỉ để thử nhánh lỗi và tham số, không dùng kết quả
 * của lớp này để khẳng định SQL hoặc chống trùng lịch đã chạy đúng.
 * PostgreSQL thật được kiểm riêng trong ReservationWriteAdapterIntegrationTest.
 */
class ReservationWriteAdapterTest {

    private static final Instant CREATED = Instant.parse("2030-09-01T01:02:03.123456Z");
    private static final Instant START = Instant.parse("2030-09-30T03:00:00Z");
    private static final Instant END = START.plus(Duration.ofHours(2));

    private NamedParameterJdbcTemplate jdbc;
    private SqlLoader loader;
    private ReservationWriteAdapter adapter;
    private String sql;

    /** Tạo adapter cô lập với nội dung SQL thật nhưng không kết nối database. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        loader = mock(SqlLoader.class);
        sql = new SqlLoader().load(ReservationSqlPaths.INSERT);
        when(loader.load(ReservationSqlPaths.INSERT)).thenReturn(sql);
        when(loader.load(ReservationSqlPaths.UPDATE_STATUS))
                .thenReturn(new SqlLoader().load(ReservationSqlPaths.UPDATE_STATUS));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        adapter = new ReservationWriteAdapter(jdbc, loader, transactions);
    }

    /** Kiểm chỉ tải SQL một lần và mỗi insert chỉ có một lệnh JDBC, không đọc trước. */
    @Test
    void loadsSqlOnceAndWritesWithoutPrecheck() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(41L))
                .thenReturn(List.of(42L));
        assertEquals(41L, adapter.insert(held()).orElseThrow());
        assertEquals(42L, adapter.insert(held()).orElseThrow());
        verify(loader).load(ReservationSqlPaths.INSERT);
        verify(loader).load(ReservationSqlPaths.UPDATE_STATUS);
        verify(loader).load(ReservationSqlPaths.MOVE_COMPLIANCE_HOLD_START);
        verify(loader).load(ReservationSqlPaths.RELEASE_EXPIRED_HOLDS);
        verifyNoMoreInteractions(loader);
        verify(jdbc, times(2)).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm mọi tham số HELD có đúng giá trị, kiểu SQL và thời gian UTC, không tính lại TTL/đệm. */
    @Test
    void bindsHeldFieldsWithExplicitJdbcTypes() {
        Reservation reservation = held();
        SqlParameterSource parameters = captureParameters(reservation);
        assertEquals(11, parameters.getParameterNames().length);
        assertParameter(parameters, "code", reservation.code(), Types.VARCHAR);
        assertParameter(parameters, "vehicleId", reservation.vehicleId(), Types.BIGINT);
        assertParameter(parameters, "kind", "RENTAL", Types.VARCHAR);
        assertParameter(parameters, "status", "HELD", Types.VARCHAR);
        assertParameter(parameters, "bookingCode", reservation.bookingCode(), Types.VARCHAR);
        assertParameter(parameters, "reason", null, Types.VARCHAR);
        assertParameter(parameters, "startInclusive", START.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "endExclusive", END.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "holdExpiresAt", CREATED.plus(Duration.ofMinutes(90)).atOffset(ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "createdAt", CREATED.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "statusChangedAt", CREATED.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** Kiểm cận trên, mã đơn và TTL null của compliance vẫn có kiểu JDBC, lý do giữ nguyên. */
    @Test
    void bindsUnboundedComplianceAndNullableFields() {
        Reservation reservation = Reservation.createBlocked("adapter-unit-compliance", 97L,
                ReservationPeriod.unboundedFrom(START), ReservationKind.COMPLIANCE_HOLD,
                "  Document expired  ", CREATED);
        SqlParameterSource parameters = captureParameters(reservation);
        assertParameter(parameters, "kind", "COMPLIANCE_HOLD", Types.VARCHAR);
        assertParameter(parameters, "status", "BLOCKED", Types.VARCHAR);
        assertParameter(parameters, "endExclusive", null, Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "holdExpiresAt", null, Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "bookingCode", null, Types.VARCHAR);
        assertParameter(parameters, "reason", "  Document expired  ", Types.VARCHAR);
    }

    /** Kiểm không nhầm mốc đổi trạng thái với mốc tạo khi aggregate đã chuyển trạng thái. */
    @Test
    void bindsStatusChangeTimeSeparatelyFromCreationTime() {
        Instant changedAt = CREATED.plusSeconds(600);
        SqlParameterSource parameters = captureParameters(held().confirm(changedAt));
        assertParameter(parameters, "status", "CONFIRMED", Types.VARCHAR);
        assertParameter(parameters, "createdAt", CREATED.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        assertParameter(parameters, "statusChangedAt", changedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** Kiểm không có dòng RETURNING thì port trả rỗng. */
    @Test
    void returnsEmptyForNoReturnedRow() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());
        assertTrue(adapter.insert(held()).isEmpty());
    }

    /** Kiểm số dòng trả về bất thường phải báo lỗi, không lấy đại ID đầu tiên. */
    @Test
    void rejectsMultipleReturnedIds() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(41L, 42L));
        IncorrectResultSizeDataAccessException failure = assertThrows(
                IncorrectResultSizeDataAccessException.class, () -> adapter.insert(held()));
        assertEquals(1, failure.getExpectedSize());
        assertEquals(2, failure.getActualSize());
    }

    /** Kiểm ID null phải báo lỗi chứ không được hiểu là trùng mã. */
    @Test
    void rejectsNullReturnedId() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(Collections.singletonList(null));
        assertThrows(DataRetrievalFailureException.class, () -> adapter.insert(held()));
    }

    /** Kiểm aggregate null bị từ chối trước khi chạm JDBC. */
    @Test
    void rejectsNullBeforeCallingJdbc() {
        assertThrows(NullPointerException.class, () -> adapter.insert(null));
        verifyNoInteractions(jdbc);
    }

    /** Kiểm chỉ lỗi đủ bốn dấu hiệu được dịch và vẫn giữ nguyên cause JDBC. */
    @Test
    void translatesExactOverlapAndPreservesCause() {
        DataIntegrityViolationException failure = postgresFailure(
                "23P01", "availability", "reservation", "reservation_no_overlap");
        failNextInsert(failure);
        ReservationOverlapException actual = assertThrows(
                ReservationOverlapException.class, () -> adapter.insert(held()));
        assertSame(failure, actual.getCause());
    }

    /**
     * Kiểm sai hoặc thiếu một dấu hiệu thì truyền nguyên lỗi, kể cả CHECK và UNIQUE.
     *
     * @param state SQLSTATE cần thử
     * @param schema schema do driver cung cấp
     * @param table bảng do driver cung cấp
     * @param constraint ràng buộc do driver cung cấp
     */
    @ParameterizedTest
    @MethodSource("nonMatchingDatabaseErrors")
    void propagatesErrorsWithoutAllOverlapIdentifiers(
            String state, String schema, String table, String constraint
    ) {
        DataIntegrityViolationException failure = postgresFailure(state, schema, table, constraint);
        failNextInsert(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> adapter.insert(held())));
    }

    /** Kiểm SQLSTATE trùng lịch nhưng thiếu chi tiết server không được tự suy thành lỗi xe bận. */
    @Test
    void propagatesPostgresErrorWithoutServerDetails() {
        PSQLException postgres = mock(PSQLException.class);
        when(postgres.getSQLState()).thenReturn("23P01");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Storage failed.", postgres);
        failNextInsert(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> adapter.insert(held())));
    }

    /** Kiểm chuỗi thông báo giống trùng lịch không thay thế được thông tin có cấu trúc. */
    @Test
    void doesNotClassifyErrorByMessageText() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "23P01 availability reservation reservation_no_overlap", new IllegalStateException("Storage failed."));
        failNextInsert(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> adapter.insert(held())));
    }

    /** Kiểm lỗi mất kết nối không được nuốt hoặc đổi thành lỗi trùng lịch. */
    @Test
    void propagatesConnectionFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Connection unavailable.");
        failNextInsert(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> adapter.insert(held())));
    }

    /** Tạo HELD có TTL 90 phút để phát hiện adapter tự gán lại một giờ. */
    private static Reservation held() {
        return Reservation.createHeld("adapter-unit-held", 97L, ReservationPeriod.finite(START, END),
                "adapter-unit-booking", Duration.ofMinutes(90), CREATED);
    }

    /** Gọi insert và thu bộ tham số thực sự được gửi xuống JDBC. */
    private SqlParameterSource captureParameters(Reservation reservation) {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenReturn(List.of(41L));
        assertEquals(41L, adapter.insert(reservation).orElseThrow());
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForList(eq(sql), captor.capture(), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
        return captor.getValue();
    }

    /** Kiểm đồng thời giá trị và kiểu SQL, kể cả giá trị null. */
    private static void assertParameter(SqlParameterSource parameters, String name, Object value, int type) {
        assertTrue(parameters.hasValue(name), name);
        assertEquals(value, parameters.getValue(name), name);
        assertEquals(type, parameters.getSqlType(name), name);
    }

    /** Thiết lập lỗi cho đúng lệnh JDBC insert mà adapter đang dùng. */
    private void failNextInsert(RuntimeException failure) {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenThrow(failure);
    }

    /** Tạo lỗi có metadata mô phỏng; không dùng để chứng minh PostgreSQL thực sự từ chối dữ liệu. */
    private static DataIntegrityViolationException postgresFailure(
            String state, String schema, String table, String constraint
    ) {
        PSQLException postgres = mock(PSQLException.class);
        ServerErrorMessage details = mock(ServerErrorMessage.class);
        when(postgres.getSQLState()).thenReturn(state);
        when(postgres.getServerErrorMessage()).thenReturn(details);
        when(details.getSchema()).thenReturn(schema);
        when(details.getTable()).thenReturn(table);
        when(details.getConstraint()).thenReturn(constraint);
        return new DataIntegrityViolationException("Storage failed.", postgres);
    }

    /** Cung cấp các lỗi lệch từng dấu hiệu để phát hiện bộ lọc nhận diện quá rộng. */
    private static Stream<Arguments> nonMatchingDatabaseErrors() {
        return Stream.of(
                Arguments.of("23514", "availability", "reservation", "reservation_no_overlap"),
                Arguments.of("23505", "availability", "reservation", "reservation_no_overlap"),
                Arguments.of(null, "availability", "reservation", "reservation_no_overlap"),
                Arguments.of("23P01", "other", "reservation", "reservation_no_overlap"),
                Arguments.of("23P01", null, "reservation", "reservation_no_overlap"),
                Arguments.of("23P01", "availability", "other", "reservation_no_overlap"),
                Arguments.of("23P01", "availability", null, "reservation_no_overlap"),
                Arguments.of("23P01", "availability", "reservation", "other_constraint"),
                Arguments.of("23P01", "availability", "reservation", null),
                Arguments.of("23514", "availability", "reservation", "chk_hold_has_ttl"),
                Arguments.of("23505", "availability", "reservation", "uq_reservation_code")
        );
    }
}
