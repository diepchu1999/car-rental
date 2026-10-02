package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm bind tham số và truyền lỗi của truy vấn xe bận, không giả lập semantics của PostgreSQL. */
class ReservationBusyReadAdapterTest {

    private static final ReservationPeriod PERIOD = ReservationPeriod.finite(
            OffsetDateTime.parse("2030-10-01T10:00:00+07:00").toInstant(),
            OffsetDateTime.parse("2030-10-01T14:00:00+07:00").toInstant());
    private NamedParameterJdbcTemplate jdbc;
    private SqlLoader loader;
    private ReservationReadAdapter adapter;
    private String sql;

    /** Tải SQL thật và giả JDBC để quan sát tham số truyền xuống driver. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        loader = mock(SqlLoader.class);
        sql = new SqlLoader().load(ReservationSqlPaths.FIND_BUSY_VEHICLE_IDS);
        when(loader.load(ReservationSqlPaths.FIND_BUSY_VEHICLE_IDS)).thenReturn(sql);
        adapter = new ReservationReadAdapter(jdbc, loader);
    }

    /** Kiểm ID kiểu BIGINT, hai mốc UTC và tập kết quả không trùng; SQL chỉ tải một lần. */
    @Test
    void bindsCandidatesAndBufferedPeriodWithoutChangingInstants() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(41L, 41L));
        assertEquals(Set.of(41L), adapter.findBusyVehicleIds(PERIOD, List.of(41L, 42L)));
        assertEquals(Set.of(41L), adapter.findBusyVehicleIds(PERIOD, List.of(41L, 42L)));
        var captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).queryForList(eq(sql), captor.capture(), eq(Long.class));
        for (SqlParameterSource parameters : captor.getAllValues()) {
            assertEquals(List.of(41L, 42L), parameters.getValue("candidateIds"));
            assertEquals(Types.BIGINT, parameters.getSqlType("candidateIds"));
            assertEquals(PERIOD.startInclusive().atOffset(ZoneOffset.UTC), parameters.getValue("startInclusive"));
            assertEquals(PERIOD.endExclusive().atOffset(ZoneOffset.UTC), parameters.getValue("endExclusive"));
            assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, parameters.getSqlType("startInclusive"));
            assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, parameters.getSqlType("endExclusive"));
        }
        verify(loader).load(ReservationSqlPaths.FIND_BUSY_VEHICLE_IDS);
        verify(loader).load(ReservationSqlPaths.FIND_BY_CODE);
        verifyNoMoreInteractions(loader, jdbc);
    }

    /** Kiểm adapter không tạo câu IN rỗng khi port được gọi với tập rỗng. */
    @Test
    void skipsSqlForEmptyCandidates() {
        assertTrue(adapter.findBusyVehicleIds(PERIOD, List.of()).isEmpty());
        verifyNoInteractions(jdbc);
    }

    /** Kiểm SQL không có dòng trả tập rỗng, không tạo ID giả. */
    @Test
    void returnsEmptyWhenNoRowsMatch() {
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenReturn(List.of());
        assertTrue(adapter.findBusyVehicleIds(PERIOD, List.of(41L)).isEmpty());
    }

    /** Kiểm lỗi kết nối được truyền nguyên trạng, không bị coi là xe rảnh. */
    @Test
    void propagatesDatabaseFailure() {
        var failure = new DataAccessResourceFailureException("Connection unavailable.");
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> adapter.findBusyVehicleIds(PERIOD, List.of(41L))));
    }
}
