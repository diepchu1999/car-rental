package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm dịch đúng metadata lỗi và không nuốt lỗi hạ tầng; SQL thật được kiểm ở integration test. */
class StaffBranchAssignmentWriteAdapterTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StaffBranchAssignmentWriteAdapter adapter = new StaffBranchAssignmentWriteAdapter(jdbc, new SqlLoader());
    private final StaffBranchAssignment assignment = new StaffBranchAssignment("TK-ABC123", 42L,
            AccountRole.BRANCH_MANAGER, Instant.parse("2026-10-13T03:00:00Z"));

    /** Chỉ đầy đủ bốn trường metadata khớp mới dịch sang BRANCH_MANAGER_ALREADY_ASSIGNED. */
    @Test
    void translatesOnlyExactManagerConflict() {
        var failure = databaseFailure("23505", "identity", "staff_branch_assignment", "uq_staff_branch_manager");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
        var translated = assertThrowsExactly(DomainException.class, () -> adapter.save(assignment));
        assertEquals(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED, translated.errorCode());
        assertEquals(DomainException.Category.CONFLICT, translated.category());
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    /** Sai bất kỳ metadata nào đều giữ nguyên exception, kể cả lỗi CHECK, FK, deadlock và timeout. */
    @Test
    void preservesAllOtherDatabaseFailures() {
        for (var failure : new DataIntegrityViolationException[]{
                databaseFailure("23514", "identity", "staff_branch_assignment", "uq_staff_branch_manager"),
                databaseFailure("23503", "identity", "staff_branch_assignment", "fk_staff_branch_account"),
                databaseFailure("40P01", "identity", "staff_branch_assignment", "uq_staff_branch_manager"),
                databaseFailure("55P03", "identity", "staff_branch_assignment", "uq_staff_branch_manager"),
                databaseFailure("23505", "other", "staff_branch_assignment", "uq_staff_branch_manager"),
                databaseFailure("23505", "identity", "other", "uq_staff_branch_manager"),
                databaseFailure("23505", "identity", "staff_branch_assignment", "uq_staff_branch_account"),
                databaseFailure("23505", null, null, null),
                new DataIntegrityViolationException("Unclassified integrity failure.")}) {
            reset(jdbc);
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
            assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> adapter.save(assignment)));
            verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
        }
    }

    /** Lỗi kết nối không được trả false như tài khoản không tồn tại. */
    @Test
    void propagatesConnectionFailure() {
        var failure = new DataAccessResourceFailureException("Database unavailable.");
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> adapter.save(assignment)));
    }

    /** Chỉ số dòng 0 và 1 là hợp lệ; mọi giá trị khác là lỗi hạ tầng. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void rejectsUnexpectedAffectedRowCounts(int rows) {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(rows);
        assertThrowsExactly(IncorrectUpdateSemanticsDataAccessException.class, () -> adapter.save(assignment));
    }

    /** Bản gán null bị chặn trước khi gọi JDBC. */
    @Test
    void rejectsMissingAssignmentBeforeJdbc() {
        assertThrowsExactly(NullPointerException.class, () -> adapter.save(null));
        verifyNoInteractions(jdbc);
    }

    /** Dựng lỗi có metadata có cấu trúc để kiểm từng điều kiện, không dựa vào thông báo tiếng Anh. */
    private static DataIntegrityViolationException databaseFailure(String state, String schema, String table, String constraint) {
        var postgres = mock(PSQLException.class);
        var details = mock(ServerErrorMessage.class);
        when(postgres.getSQLState()).thenReturn(state);
        when(postgres.getServerErrorMessage()).thenReturn(details);
        when(details.getSchema()).thenReturn(schema);
        when(details.getTable()).thenReturn(table);
        when(details.getConstraint()).thenReturn(constraint);
        return new DataIntegrityViolationException("Database rejected the assignment.", postgres);
    }
}
