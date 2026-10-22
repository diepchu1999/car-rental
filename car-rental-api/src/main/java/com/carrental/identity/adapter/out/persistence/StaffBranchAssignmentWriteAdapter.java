package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * BR-807: ghi trực tiếp để UNIQUE phân xử quản lý, không đọc trước xem chi nhánh còn chỗ.
 * Chỉ chuyển đúng xung đột index quản lý; transaction và rollback thuộc application.
 */
@Repository
class StaffBranchAssignmentWriteAdapter implements WriteStaffBranchAssignmentPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String saveSql;

    /** Tải SQL có tham số một lần, không nối mã hoặc ID vào câu lệnh. */
    StaffBranchAssignmentWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.saveSql = loader.load("sql/identity/save_staff_branch_assignment.sql");
    }

    /** Không nuốt xung đột quản lý bằng DO NOTHING; lỗi đó phải làm rollback toàn bộ use case. */
    @Override
    public boolean save(StaffBranchAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment must not be null.");
        var parameters = new MapSqlParameterSource()
                .addValue("accountCode", assignment.accountCode(), Types.VARCHAR)
                .addValue("branchId", assignment.branchId(), Types.BIGINT)
                .addValue("staffRole", assignment.staffRole().name(), Types.VARCHAR)
                .addValue("assignedAt", assignment.assignedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        try {
            int rows = jdbc.update(saveSql, parameters);
            if (rows == 1) return true;
            if (rows == 0) return false;
            throw new IncorrectUpdateSemanticsDataAccessException("Expected zero or one staff branch assignment row, but got: " + rows);
        } catch (DataIntegrityViolationException failure) {
            if (isManagerConflict(failure)) {
                throw DomainException.conflict(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED);
            }
            throw failure;
        }
    }

    /** Nhận diện SQLSTATE, schema, bảng và index bằng metadata, không dò chuỗi thông báo lỗi. */
    private static boolean isManagerConflict(DataIntegrityViolationException failure) {
        if (!(failure.getMostSpecificCause() instanceof PSQLException postgres)
                || !"23505".equals(postgres.getSQLState())) return false;
        var details = postgres.getServerErrorMessage();
        return details != null && "identity".equals(details.getSchema())
                && "staff_branch_assignment".equals(details.getTable())
                && "uq_staff_branch_manager".equals(details.getConstraint());
    }
}
