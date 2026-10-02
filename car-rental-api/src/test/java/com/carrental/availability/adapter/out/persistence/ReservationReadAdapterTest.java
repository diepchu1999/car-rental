package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.domain.Reservation;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/** Kiểm hợp đồng truy vấn và lỗi ánh xạ; không dùng mock để chứng minh SQL thật. */
class ReservationReadAdapterTest {

    private NamedParameterJdbcTemplate jdbc;
    private SqlLoader loader;
    private ReservationReadAdapter adapter;
    private String sql;

    /** Tạo adapter với SQL thật và JDBC giả để quan sát tham số, số lần gọi. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        loader = mock(SqlLoader.class);
        sql = new SqlLoader().load(ReservationSqlPaths.FIND_BY_CODE);
        when(loader.load(ReservationSqlPaths.FIND_BY_CODE)).thenReturn(sql);
        adapter = new ReservationReadAdapter(jdbc, loader);
    }

    /** Kiểm SQL tải một lần, mã giữ nguyên và được bind VARCHAR thay vì nối chuỗi. */
    @Test
    void loadsSqlOnceAndBindsCodeWithoutNormalization() {
        Reservation expected = mock(Reservation.class);
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(ReservationRowMappers.AGGREGATE)))
                .thenReturn(List.of(expected));
        String firstCode = " KL-ABC123 ";
        String secondCode = "KL-' OR '1'='1";
        assertSame(expected, adapter.loadAggregate(firstCode).orElseThrow());
        assertSame(expected, adapter.loadAggregate(secondCode).orElseThrow());
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).query(eq(sql), parameters.capture(), same(ReservationRowMappers.AGGREGATE));
        assertEquals(List.of(firstCode, secondCode), parameters.getAllValues().stream()
                .map(value -> value.getValue("code")).toList());
        for (SqlParameterSource value : parameters.getAllValues()) {
            assertEquals(Types.VARCHAR, value.getSqlType("code"));
        }
        verify(loader).load(ReservationSqlPaths.FIND_BY_CODE);
        verify(loader).load(ReservationSqlPaths.FIND_BUSY_VEHICLE_IDS);
        verifyNoMoreInteractions(loader, jdbc);
    }

    /** Kiểm truy vấn không có dòng mới được chuyển thành Optional rỗng. */
    @Test
    void returnsEmptyForMissingRow() {
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(ReservationRowMappers.AGGREGATE)))
                .thenReturn(List.of());
        assertTrue(adapter.loadAggregate("KL-MISSING").isEmpty());
    }

    /** Kiểm nhiều dòng bất thường phải báo lỗi, không tùy tiện chọn dòng đầu. */
    @Test
    void rejectsMultipleRows() {
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(ReservationRowMappers.AGGREGATE)))
                .thenReturn(List.of(mock(Reservation.class), mock(Reservation.class)));
        assertThrows(IncorrectResultSizeDataAccessException.class, () -> adapter.loadAggregate("KL-ABC123"));
    }

    /** Kiểm lỗi kết nối không bị nuốt thành kết quả không tìm thấy. */
    @Test
    void propagatesStorageFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Connection unavailable.");
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(ReservationRowMappers.AGGREGATE)))
                .thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> adapter.loadAggregate("KL-ABC123")));
    }

    /** Kiểm lỗi bất biến khi khôi phục aggregate không bị nuốt thành không tìm thấy. */
    @Test
    void propagatesAggregateRestorationFailure() {
        DomainException failure = DomainException.invalidInput("Stored reservation is invalid.");
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(ReservationRowMappers.AGGREGATE)))
                .thenThrow(failure);
        assertSame(failure, assertThrows(DomainException.class, () -> adapter.loadAggregate("KL-ABC123")));
    }

    /** Kiểm upper_inf=false mà thiếu upper phải lỗi, không biến thành khóa vô hạn. */
    @Test
    void mapperRejectsMissingFiniteUpperBound() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("starts_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2030-10-01T10:00:00+07:00"));
        when(row.getBoolean("end_unbounded")).thenReturn(false);
        assertThrows(DomainException.class, () -> ReservationRowMappers.AGGREGATE.mapRow(row, 0));
        verify(row).getObject("ends_at", OffsetDateTime.class);
    }
}
