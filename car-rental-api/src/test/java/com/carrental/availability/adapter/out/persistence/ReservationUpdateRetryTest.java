package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm UPDATE trạng thái, dọn hết hạn và dời mốc giấy tờ tuân cùng hợp đồng savepoint/retry của ADR-0005. */
class ReservationUpdateRetryTest {

    private static final Instant CHANGED = Instant.parse("2030-01-01T00:00:00Z");
    private NamedParameterJdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private ReservationWriteAdapter adapter;
    private SqlLoader loader;
    private final List<SimpleTransactionStatus> statuses = new ArrayList<>();

    /** Mô phỏng từng savepoint riêng để kiểm rollback trước retry; không dùng mock làm bằng chứng CSDL thật. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertEquals(TransactionDefinition.PROPAGATION_NESTED,
                    invocation.<TransactionDefinition>getArgument(0).getPropagationBehavior());
            var status = new SimpleTransactionStatus();
            statuses.add(status);
            return status;
        });
        loader = new SqlLoader();
        adapter = new ReservationWriteAdapter(jdbc, loader, transactions);
    }

    /** Kiểm một/hai deadlock được rollback trước lần kế tiếp và mọi lần dùng đúng cùng bộ tham số. */
    @ParameterizedTest
    @CsvSource({"status, 1", "status, 2", "expiry, 1", "expiry, 2", "move, 1", "move, 2"})
    void retriesAfterRollbackWithIdenticalParameters(String operation, int deadlocks) {
        String sql = sqlFor(operation);
        int[] calls = {0};
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenAnswer(invocation -> {
            if (calls[0]++ < deadlocks) {
                throw postgresFailure("40P01");
            }
            return 1;
        });
        assertEquals(1, invoke(operation));
        InOrder order = inOrder(transactions, jdbc);
        for (int index = 0; index <= deadlocks; index++) {
            order.verify(transactions).getTransaction(any(TransactionDefinition.class));
            order.verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
            if (index < deadlocks) {
                order.verify(transactions).rollback(statuses.get(index));
            } else {
                order.verify(transactions).commit(statuses.get(index));
            }
        }
        order.verifyNoMoreInteractions();
        var captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(deadlocks + 1)).update(eq(sql), captor.capture());
        for (SqlParameterSource parameters : captor.getAllValues()) {
            assertSame(captor.getAllValues().getFirst(), parameters);
        }
    }

    /** Kiểm dừng đúng lần ba và ném lại object deadlock đầu tiên, không biến thành false hoặc số không. */
    @ParameterizedTest
    @ValueSource(strings = {"status", "expiry", "move"})
    void exhaustsThreeAttemptsAndRethrowsFirstDeadlock(String operation) {
        String sql = sqlFor(operation);
        DataAccessException first = postgresFailure("40P01");
        DataAccessException second = postgresFailure("40P01");
        DataAccessException third = postgresFailure("40P01");
        when(jdbc.update(eq(sql), any(SqlParameterSource.class)))
                .thenThrow(first, second, third);
        assertSame(first, assertThrows(DataAccessException.class, () -> invoke(operation)));
        verify(jdbc, times(3)).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
        assertEquals(3, statuses.size());
        statuses.forEach(status -> verify(transactions).rollback(status));
        verify(transactions, never()).commit(any());
    }

    /** Kiểm SQLSTATE khác, kể cả lock timeout và exclusion violation, không được thử lại hay đổi lỗi. */
    @ParameterizedTest
    @CsvSource({"status, 55P03", "expiry, 55P03", "status, 40001", "expiry, 40001",
            "status, 23P01", "expiry, 23P01", "status, 08006", "expiry, 08006",
            "move, 55P03", "move, 40001", "move, 23P01", "move, 08006"})
    void doesNotRetryOtherSqlStates(String operation, String state) {
        String sql = sqlFor(operation);
        DataAccessException failure = postgresFailure(state);
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessException.class, () -> invoke(operation)));
        verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
        assertEquals(1, statuses.size());
        verify(transactions).rollback(statuses.getFirst());
    }

    /** Kiểm không suy deadlock từ thông báo hoặc SQLException không phải của driver PostgreSQL. */
    @ParameterizedTest
    @ValueSource(strings = {"status", "expiry", "move"})
    void doesNotInferDeadlockFromMessageOrNonPostgresCause(String operation) {
        String sql = sqlFor(operation);
        var failure = new PessimisticLockingFailureException("40P01 deadlock detected",
                new SQLException("deadlock detected", "40P01"));
        when(jdbc.update(eq(sql), any(SqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessException.class, () -> invoke(operation)));
        verify(jdbc).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm sau deadlock, lỗi timeout phải truyền đúng object timeout và dừng ngay. */
    @ParameterizedTest
    @ValueSource(strings = {"status", "expiry", "move"})
    void stopsOnDifferentFailureAfterDeadlock(String operation) {
        String sql = sqlFor(operation);
        DataAccessException timeout = postgresFailure("55P03");
        DataAccessException deadlock = postgresFailure("40P01");
        when(jdbc.update(eq(sql), any(SqlParameterSource.class)))
                .thenThrow(deadlock, timeout);
        assertSame(timeout, assertThrows(DataAccessException.class, () -> invoke(operation)));
        verify(jdbc, times(2)).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm retry có thể kết thúc bằng không dòng khớp: giữ semantics CAS hoặc lượt dọn rỗng. */
    @ParameterizedTest
    @ValueSource(strings = {"status", "expiry", "move"})
    void preservesZeroRowsAfterDeadlock(String operation) {
        String sql = sqlFor(operation);
        DataAccessException deadlock = postgresFailure("40P01");
        when(jdbc.update(eq(sql), any(SqlParameterSource.class)))
                .thenThrow(deadlock).thenReturn(0);
        assertEquals(0, invoke(operation));
        verify(jdbc, times(2)).update(eq(sql), any(SqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
        assertEquals(2, statuses.size());
        verify(transactions).rollback(statuses.getFirst());
        verify(transactions).commit(statuses.getLast());
    }

    /** Chọn đúng tài nguyên SQL của thao tác đang kiểm, không dựng SQL giả trong Java. */
    private String sqlFor(String operation) {
        return loader.load(switch (operation) {
            case "status" -> ReservationSqlPaths.UPDATE_STATUS;
            case "expiry" -> ReservationSqlPaths.RELEASE_EXPIRED_HOLDS;
            case "move" -> ReservationSqlPaths.MOVE_COMPLIANCE_HOLD_START;
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        });
    }

    /** Gọi adapter với mốc cố định; chuẩn hóa boolean thành số chỉ trong test để dùng chung assertions. */
    private int invoke(String operation) {
        return switch (operation) {
            case "status" -> adapter.updateStatus("KL-RETRY1", ReservationStatus.HELD,
                    ReservationStatus.CONFIRMED, CHANGED) ? 1 : 0;
            case "expiry" -> adapter.releaseExpiredHolds(CHANGED);
            case "move" -> adapter.moveComplianceHoldStart("KL-RETRY1", CHANGED, CHANGED.plusSeconds(86400)) ? 1 : 0;
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    /** Tạo lỗi có SQLSTATE của PostgreSQL; không dùng chuỗi thông báo để quyết định retry. */
    private static DataAccessException postgresFailure(String state) {
        PSQLException cause = mock(PSQLException.class);
        when(cause.getSQLState()).thenReturn(state);
        return new PessimisticLockingFailureException("Database failure.", cause);
    }
}
