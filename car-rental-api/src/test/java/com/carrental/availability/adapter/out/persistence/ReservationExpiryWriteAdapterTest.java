package com.carrental.availability.adapter.out.persistence;

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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm tham số và xử lý kết quả UPDATE dọn hết hạn; semantics CSDL kiểm riêng. */
class ReservationExpiryWriteAdapterTest {

    private static final Instant NOW = Instant.parse("2030-10-01T00:00:00.123456Z");
    private NamedParameterJdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private ReservationWriteAdapter adapter;
    private String sql;

    /** Tạo adapter dùng SQL thật và mock JDBC, không khởi động database. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertEquals(TransactionDefinition.PROPAGATION_NESTED,
                    invocation.<TransactionDefinition>getArgument(0).getPropagationBehavior());
            return new SimpleTransactionStatus();
        });
        SqlLoader loader = new SqlLoader();
        sql = loader.load(ReservationSqlPaths.RELEASE_EXPIRED_HOLDS);
        adapter = new ReservationWriteAdapter(jdbc, loader, transactions);
    }

    /** Kiểm một lệnh ghi với mốc UTC đúng kiểu, trả đúng số dòng và dùng scope NESTED. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20})
    void bindsSingleCutoffAndReturnsCount(int count) {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(count);
        assertEquals(count, adapter.releaseExpiredHolds(NOW));
        var captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq(sql), captor.capture());
        assertArrayEquals(new String[]{"expiredAt"}, captor.getValue().getParameterNames());
        assertEquals(NOW.atOffset(ZoneOffset.UTC), captor.getValue().getValue("expiredAt"));
        assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, captor.getValue().getSqlType("expiredAt"));
        verifyNoMoreInteractions(jdbc);
        verify(transactions).getTransaction(any(TransactionDefinition.class));
        verify(transactions).commit(any());
        verifyNoMoreInteractions(transactions);
    }

    /** Kiểm thiếu mốc dọn bị từ chối trước JDBC. */
    @Test
    void rejectsNullCutoff() {
        assertThrows(NullPointerException.class, () -> adapter.releaseExpiredHolds(null));
        verifyNoInteractions(jdbc, transactions);
    }

    /** Kiểm số dòng âm bất thường phải lỗi, không diễn giải thành một lượt dọn thành công. */
    @Test
    void rejectsNegativeCount() {
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenReturn(-1);
        assertThrows(IncorrectUpdateSemanticsDataAccessException.class, () -> adapter.releaseExpiredHolds(NOW));
    }

    /** Kiểm lỗi kết nối truyền nguyên trạng, không được thử lại như deadlock. */
    @Test
    void propagatesStorageFailure() {
        var failure = new DataAccessResourceFailureException("Storage unavailable.");
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> adapter.releaseExpiredHolds(NOW)));
        verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }
}
