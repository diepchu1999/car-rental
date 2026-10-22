package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.shared.validation.Validations;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** Đọc bản gán bằng mã tài khoản, chỉ ghép bảng trong identity theo ADR-0008. */
@Repository
class StaffBranchAssignmentReadAdapter implements ReadStaffBranchAssignmentPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String findSql;

    /** Tải SQL khi dựng adapter, không đọc lại tài nguyên ở mỗi lần gọi. */
    StaffBranchAssignmentReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.findSql = loader.load("sql/identity/find_staff_branch_assignment.sql");
    }

    /** Không trả rỗng khi có lỗi JDBC hoặc dữ liệu lưu không hợp lệ. */
    @Override
    public Optional<StaffBranchAssignment> findByAccountCode(String accountCode) {
        return DataAccessUtils.optionalResult(jdbc.query(findSql,
                Map.of("accountCode", Validations.requiredText(accountCode, "accountCode")), this::mapRow));
    }

    /** Dữ liệu hỏng trong CSDL là lỗi nội bộ, không biến thành lỗi 400/422 của người gọi. */
    private StaffBranchAssignment mapRow(ResultSet row, int rowNum) throws SQLException {
        try {
            String role = row.getString("staff_role");
            OffsetDateTime at = row.getObject("assigned_at", OffsetDateTime.class);
            return new StaffBranchAssignment(row.getString("account_code"), row.getLong("branch_id"),
                    role == null ? null : AccountRole.valueOf(role), at == null ? null : at.toInstant());
        } catch (DomainException | IllegalArgumentException invalidStoredData) {
            throw new IllegalStateException("Stored staff branch assignment violates the identity contract.", invalidStoredData);
        }
    }
}
