package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.shared.rental.RentalType;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.sql.Types;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Quan sát bind và lỗi hạ tầng; semantics truy vấn được kiểm riêng bằng PostgreSQL thật. */
class VehicleSearchReadAdapterTest {
    private NamedParameterJdbcTemplate jdbc;
    private SqlLoader loader;
    private VehicleReadAdapter adapter;
    private String sql;

    /** Tải SQL thật một lần, chỉ giả JDBC để kiểm tham số mà không mở database. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        loader = mock(SqlLoader.class);
        sql = new SqlLoader().load(VehicleSqlPaths.FIND_SEARCH_CANDIDATES);
        when(loader.load(VehicleSqlPaths.FIND_SEARCH_CANDIDATES)).thenReturn(sql);
        adapter = new VehicleReadAdapter(jdbc, loader);
    }

    /** Bộ lọc null có kiểu JDBC rõ, và adapter không phải tải lại SQL mỗi lần gọi. */
    @Test
    void bindsNullableFiltersWithExplicitTypes() {
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(VehicleRowMappers.SEARCH_CANDIDATE)))
                .thenReturn(List.of());
        var query = ListSearchVehiclesQuery.from(List.of(42L), RentalType.DAILY,
                null, null, null, null, null, null);
        adapter.findSearchCandidates(query);
        adapter.findSearchCandidates(query);
        var captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).query(eq(sql), captor.capture(), same(VehicleRowMappers.SEARCH_CANDIDATE));
        for (var parameters : captor.getAllValues()) {
            assertEquals(List.of(42L), parameters.getValue("branch_ids"));
            assertEquals(Types.INTEGER, parameters.getSqlType("seats"));
            assertNull(parameters.getValue("seats"));
            for (String name : List.of("transmission", "fuel_type", "make", "model")) {
                assertEquals(Types.VARCHAR, parameters.getSqlType(name));
                assertNull(parameters.getValue(name));
            }
        }
        verify(loader).load(VehicleSqlPaths.FIND_SEARCH_CANDIDATES);
        verify(loader).load(VehicleSqlPaths.FIND_BY_CODE);
        verifyNoMoreInteractions(loader, jdbc);
    }

    /** Adapter tự bảo vệ câu IN rỗng, kể cả được gọi trực tiếp qua out-port. */
    @Test
    void emptyBranchesNeverExecuteSql() {
        assertTrue(adapter.findSearchCandidates(ListSearchVehiclesQuery.from(List.of(),
                RentalType.DAILY, null, null, null, null, null, null)).isEmpty());
        verifyNoInteractions(jdbc);
    }

    /** Lỗi kết nối phải được truyền nguyên trạng, không đổi thành danh sách xe rỗng. */
    @Test
    void propagatesDatabaseFailure() {
        var failure = new DataAccessResourceFailureException("Connection unavailable.");
        when(jdbc.query(eq(sql), any(SqlParameterSource.class), same(VehicleRowMappers.SEARCH_CANDIDATE)))
                .thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> adapter.findSearchCandidates(ListSearchVehiclesQuery.from(List.of(42L),
                        RentalType.DAILY, null, null, null, null, null, null))));
    }
}
