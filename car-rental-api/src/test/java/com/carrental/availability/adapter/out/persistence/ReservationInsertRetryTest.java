package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm retry có giới hạn và phân loại SQLSTATE; savepoint thật được chứng minh ở integration test. */
class ReservationInsertRetryTest {

    private NamedParameterJdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private ReservationWriteAdapter adapter;
    private String sql;
    private final List<SimpleTransactionStatus> statuses = new ArrayList<>();

    /** Mỗi lượt mở scope nhận một status riêng để kiểm thứ tự rollback trước khi thử lại. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertEquals(TransactionDefinition.PROPAGATION_NESTED,
                    invocation.<TransactionDefinition>getArgument(0).getPropagationBehavior());
            SimpleTransactionStatus status = new SimpleTransactionStatus();
            statuses.add(status);
            return status;
        });
        SqlLoader loader = new SqlLoader();
        sql = loader.load(ReservationSqlPaths.INSERT);
        adapter = new ReservationWriteAdapter(jdbc, loader, transactions);
    }

    /** Kiểm một/hai deadlock được rollback trước retry; cả ba lượt dùng đúng cùng bộ tham số. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void retriesAfterRollbackWithIdenticalParameters(int deadlocks) {
        int[] calls = {0};
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenAnswer(invocation -> {
                    if (calls[0]++ < deadlocks) {
                        throw postgresFailure("40P01");
                    }
                    return List.of(71L);
                });
        assertEquals(71L, adapter.insert(reservation()).orElseThrow());
        InOrder order = inOrder(transactions, jdbc);
        for (int index = 0; index <= deadlocks; index++) {
            order.verify(transactions).getTransaction(any(TransactionDefinition.class));
            order.verify(jdbc).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
            if (index < deadlocks) {
                order.verify(transactions).rollback(statuses.get(index));
            } else {
                order.verify(transactions).commit(statuses.get(index));
            }
        }
        order.verifyNoMoreInteractions();
        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(deadlocks + 1)).queryForList(eq(sql), captor.capture(), eq(Long.class));
        for (SqlParameterSource parameters : captor.getAllValues()) {
            assertSame(captor.getAllValues().getFirst(), parameters);
        }
    }

    /** Kiểm đúng ba lần rồi ném lại cùng object deadlock đầu tiên, không đổi lỗi hoặc thử lần bốn. */
    @Test
    void exhaustsThreeAttemptsAndRethrowsOriginalDeadlock() {
        DataAccessException first = postgresFailure("40P01");
        DataAccessException second = postgresFailure("40P01");
        DataAccessException third = postgresFailure("40P01");
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(first, second, third);
        assertSame(first, assertThrows(DataAccessException.class, () -> adapter.insert(reservation())));
        verify(jdbc, times(3)).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
        assertEquals(3, statuses.size());
        statuses.forEach(status -> verify(transactions).rollback(status));
        verify(transactions, never()).commit(any());
    }

    /** Kiểm cùng lớp exception Spring nhưng SQLSTATE khác (kể cả 55P03) không được thử lại. */
    @ParameterizedTest
    @ValueSource(strings = {"55P03", "40001", "57014", "08006"})
    void doesNotRetryOtherSqlStates(String state) {
        DataAccessException failure = postgresFailure(state);
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessException.class, () -> adapter.insert(reservation())));
        verify(jdbc).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
        assertEquals(1, statuses.size());
        verify(transactions).rollback(statuses.getFirst());
    }

    /** Kiểm nội dung thông báo hoặc SQLException không phải PostgreSQL không đủ điều kiện retry. */
    @Test
    void doesNotInferDeadlockFromMessageOrNonPostgresCause() {
        DataAccessException failure = new PessimisticLockingFailureException("40P01 deadlock detected",
                new SQLException("deadlock detected", "40P01"));
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessException.class, () -> adapter.insert(reservation())));
        verify(jdbc).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm gặp lock timeout sau deadlock phải dừng ngay và truyền chính lỗi timeout đó. */
    @Test
    void stopsOnDifferentFailureAfterDeadlock() {
        DataAccessException timeout = postgresFailure("55P03");
        DataAccessException deadlock = postgresFailure("40P01");
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(deadlock, timeout);
        assertSame(timeout, assertThrows(DataAccessException.class, () -> adapter.insert(reservation())));
        verify(jdbc, times(2)).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm lượt sau deadlock gặp trùng mã vẫn trả rỗng để service tự xử lý mã, không retry tại adapter. */
    @Test
    void preservesCodeCollisionResultAfterDeadlock() {
        DataAccessException deadlock = postgresFailure("40P01");
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(deadlock).thenReturn(List.of());
        assertTrue(adapter.insert(reservation()).isEmpty());
        verify(jdbc, times(2)).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Kiểm 23P01 sau retry vẫn được nhận diện đúng metadata và dịch như trước, không thử tiếp. */
    @Test
    void preservesOverlapTranslationAfterDeadlock() {
        PSQLException postgres = mock(PSQLException.class);
        ServerErrorMessage details = mock(ServerErrorMessage.class);
        when(postgres.getSQLState()).thenReturn("23P01");
        when(postgres.getServerErrorMessage()).thenReturn(details);
        when(details.getSchema()).thenReturn("availability");
        when(details.getTable()).thenReturn("reservation");
        when(details.getConstraint()).thenReturn("reservation_no_overlap");
        DataIntegrityViolationException overlap = new DataIntegrityViolationException("Overlap", postgres);
        DataAccessException deadlock = postgresFailure("40P01");
        when(jdbc.queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class)))
                .thenThrow(deadlock, overlap);
        ReservationOverlapException failure = assertThrows(ReservationOverlapException.class,
                () -> adapter.insert(reservation()));
        assertSame(overlap, failure.getCause());
        verify(jdbc, times(2)).queryForList(eq(sql), any(SqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
    }

    /** Tạo nguyên nhân PostgreSQL có SQLSTATE xác định, không dựa vào chuỗi thông báo. */
    private static DataAccessException postgresFailure(String state) {
        PSQLException cause = mock(PSQLException.class);
        when(cause.getSQLState()).thenReturn(state);
        return new PessimisticLockingFailureException("Database failure.", cause);
    }

    /** Aggregate cố định gồm cả mã, khoảng và TTL để kiểm retry không tính lại bất cứ trường nào. */
    private static Reservation reservation() {
        Instant created = Instant.parse("2030-01-01T00:00:00Z");
        Instant start = created.plusSeconds(86400);
        return Reservation.createHeld("KL-RETRY1", 42L,
                ReservationPeriod.finite(start, start.plusSeconds(7200)),
                "retry-booking", Duration.ofMinutes(90), created);
    }
}
