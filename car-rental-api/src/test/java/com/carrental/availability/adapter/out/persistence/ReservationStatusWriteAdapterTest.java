package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Kiểm hợp đồng JDBC của UPDATE có điều kiện, không dùng mock để chứng minh tranh chấp. */
class ReservationStatusWriteAdapterTest {

    private static final Instant CHANGED = Instant.parse("2030-10-01T03:04:05.123456Z");
    private NamedParameterJdbcTemplate jdbc;
    private ReservationWriteAdapter adapter;
    private String sql;

    /** Tải SQL thật và mô phỏng JDBC cho các nhánh kết quả, lỗi. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        SqlLoader loader = new SqlLoader();
        sql = loader.load(ReservationSqlPaths.UPDATE_STATUS);
        adapter = new ReservationWriteAdapter(jdbc, loader, mock(PlatformTransactionManager.class));
    }

    /** Kiểm một UPDATE duy nhất với đúng mã, trạng thái cũ/mới và thời điểm UTC. */
    @Test
    void bindsConditionalUpdateWithoutPrecheck() {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(update());
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(sql), captor.capture());
        verifyNoMoreInteractions(jdbc);
        SqlParameterSource values = captor.getValue();
        assertEquals(4, values.getParameterNames().length);
        assertEquals("KL-STAT01", values.getValue("code"));
        assertEquals("HELD", values.getValue("expectedStatus"));
        assertEquals("CONFIRMED", values.getValue("newStatus"));
        assertEquals(CHANGED.atOffset(ZoneOffset.UTC), values.getValue("changedAt"));
        assertEquals(Types.VARCHAR, values.getSqlType("code"));
        assertEquals(Types.VARCHAR, values.getSqlType("expectedStatus"));
        assertEquals(Types.VARCHAR, values.getSqlType("newStatus"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, values.getSqlType("changedAt"));
    }

    /** Kiểm không dòng khớp trả false, không ném lỗi giả hoặc thử lại. */
    @Test
    void returnsFalseForNoMatchingRow() {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(update());
        verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /**
     * Kiểm số dòng bất thường phải báo lỗi thay vì coi là thành công/xung đột.
     *
     * @param rows số dòng giả lập không thuộc tập 0 hoặc 1
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void rejectsUnexpectedRowCount(int rows) {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(rows);
        assertThrows(IncorrectUpdateSemanticsDataAccessException.class, this::update);
    }

    /** Kiểm lỗi kết nối được truyền nguyên, không biến thành false. */
    @Test
    void propagatesStorageError() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Connection unavailable.");
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, this::update));
    }

    /** Kiểm mọi tham số bắt buộc thiếu đều bị từ chối trước JDBC. */
    @Test
    void rejectsNullArgumentsBeforeJdbc() {
        assertThrows(NullPointerException.class, () -> adapter.updateStatus(null,
                ReservationStatus.HELD, ReservationStatus.CONFIRMED, CHANGED));
        assertThrows(NullPointerException.class, () -> adapter.updateStatus("KL-STAT01",
                null, ReservationStatus.CONFIRMED, CHANGED));
        assertThrows(NullPointerException.class, () -> adapter.updateStatus("KL-STAT01",
                ReservationStatus.HELD, null, CHANGED));
        assertThrows(NullPointerException.class, () -> adapter.updateStatus("KL-STAT01",
                ReservationStatus.HELD, ReservationStatus.CONFIRMED, null));
        verifyNoInteractions(jdbc);
    }

    /** Gọi một cập nhật hợp lệ về hình dạng để mỗi test chỉ thay phản hồi của JDBC. */
    private boolean update() {
        return adapter.updateStatus("KL-STAT01", ReservationStatus.HELD, ReservationStatus.CONFIRMED, CHANGED);
    }
}
