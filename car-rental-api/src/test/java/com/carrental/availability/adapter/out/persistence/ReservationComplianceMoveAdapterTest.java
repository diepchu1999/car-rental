package com.carrental.availability.adapter.out.persistence;

import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm hợp đồng JDBC dời mốc BR-015; khả năng chống ghi đè được kiểm thêm bằng PostgreSQL thật. */
class ReservationComplianceMoveAdapterTest {
    private static final Instant START = Instant.parse("2030-10-01T00:00:00.123456Z");
    private static final Instant NEXT = START.plusSeconds(86400);
    private NamedParameterJdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private ReservationWriteAdapter adapter;
    private String sql;

    /** Dùng SQL thật và mô phỏng mỗi scope NESTED, không kết nối database ở unit test. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertEquals(TransactionDefinition.PROPAGATION_NESTED,
                    invocation.<TransactionDefinition>getArgument(0).getPropagationBehavior());
            return new SimpleTransactionStatus();
        });
        var loader = new SqlLoader();
        sql = loader.load(ReservationSqlPaths.MOVE_COMPLIANCE_HOLD_START);
        adapter = new ReservationWriteAdapter(jdbc, loader, transactions);
    }

    /** Kiểm đúng ba tham số có kiểu JDBC, không truyền status_changed_at hoặc chèn/xóa bản ghi. */
    @Test
    void bindsOnlyCodeAndOldAndNewStart() {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(adapter.moveComplianceHoldStart("KL-MOVE01", START, NEXT));
        var captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(sql), captor.capture());
        verifyNoMoreInteractions(jdbc);
        SqlParameterSource values = captor.getValue();
        assertEquals(3, values.getParameterNames().length);
        assertEquals("KL-MOVE01", values.getValue("code"));
        assertEquals(Types.VARCHAR, values.getSqlType("code"));
        assertEquals(START.atOffset(ZoneOffset.UTC), values.getValue("expectedStartInclusive"));
        assertEquals(NEXT.atOffset(ZoneOffset.UTC), values.getValue("newStartInclusive"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, values.getSqlType("expectedStartInclusive"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, values.getSqlType("newStartInclusive"));
        verify(transactions).getTransaction(any(TransactionDefinition.class));
        verify(transactions).commit(any());
        verifyNoMoreInteractions(transactions);
    }

    /** Kiểm không còn khớp mốc cũ trả false, không thử lại như deadlock. */
    @Test
    void returnsFalseForNoMatch() {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(adapter.moveComplianceHoldStart("KL-MOVE01", START, NEXT));
        verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm số dòng bất thường không được coi là thành công hoặc xung đột thông thường. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void rejectsUnexpectedRowCount(int count) {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(count);
        assertThrows(IncorrectUpdateSemanticsDataAccessException.class,
                () -> adapter.moveComplianceHoldStart("KL-MOVE01", START, NEXT));
    }

    /** Kiểm thiếu bất kỳ tham số nào đều dừng trước JDBC và transaction. */
    @Test
    void rejectsNullBeforeDependencies() {
        assertThrows(NullPointerException.class, () -> adapter.moveComplianceHoldStart(null, START, NEXT));
        assertThrows(NullPointerException.class, () -> adapter.moveComplianceHoldStart("KL-MOVE01", null, NEXT));
        assertThrows(NullPointerException.class, () -> adapter.moveComplianceHoldStart("KL-MOVE01", START, null));
        verifyNoInteractions(jdbc, transactions);
    }
}
