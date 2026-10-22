package com.carrental.identity.adapter.out.persistence;

import com.carrental.shared.sql.SqlLoader;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Types;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Dữ liệu giả và SQL dùng chung cho test V010; không dùng tài khoản, số điện thoại hoặc CSDL local. */
abstract class IdentityDatabaseTestSupport {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    static final String AT = "2026-10-13T03:00:00Z";

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired SqlLoader sqlLoader;

    /** Mỗi fixture có mã, subject và số riêng, tránh lỗi trùng không liên quan điều đang kiểm. */
    MapSqlParameterSource accountParameters() {
        long sequence = SEQUENCE.incrementAndGet();
        return new MapSqlParameterSource()
                .addValue("code", "TK-%06d".formatted(sequence), Types.VARCHAR)
                .addValue("subject", "identity-db-test-" + sequence, Types.VARCHAR)
                .addValue("phone", "+8491%07d".formatted(sequence), Types.VARCHAR)
                .addValue("version", null, Types.VARCHAR)
                .addValue("consentedAt", null, Types.VARCHAR)
                .addValue("createdAt", AT, Types.VARCHAR);
    }

    /** Ghi tài khoản bằng dữ liệu thô, trả ID do PostgreSQL sinh. */
    long insertAccount(MapSqlParameterSource parameters) {
        Long id = jdbc.queryForObject(sqlLoader.load("sql/identity/insert_account_for_constraint_test.sql"),
                parameters, Long.class);
        assertNotNull(id);
        assertTrue(id > 0);
        return id;
    }

    /** Tạo tài khoản nhân viên chưa đồng ý điều khoản khách. */
    long newAccount() { return insertAccount(accountParameters()); }

    /** Dựng aggregate hợp lệ có định danh riêng cho các test adapter, chưa lưu CSDL. */
    Account accountFixture(TermsConsent consent) {
        var parameters = accountParameters();
        return Account.restore((String) parameters.getValue("code"), (String) parameters.getValue("subject"),
                new VietnamMobileNumber((String) parameters.getValue("phone")), consent);
    }

    /** Dựng tham số có kiểu để có thể cố ý gửi NULL trong phép thử phá hoại. */
    MapSqlParameterSource assignmentParameters(long accountId, long branchId, String role) {
        return new MapSqlParameterSource()
                .addValue("accountId", accountId, Types.BIGINT)
                .addValue("branchId", branchId, Types.BIGINT)
                .addValue("role", role, Types.VARCHAR)
                .addValue("assignedAt", AT, Types.VARCHAR);
    }

    /** Ghi gán chi nhánh trực tiếp, không né lỗi bằng ON CONFLICT DO NOTHING. */
    long insertAssignment(MapSqlParameterSource parameters) {
        Long id = jdbc.queryForObject(sqlLoader.load("sql/identity/insert_assignment_for_constraint_test.sql"),
                parameters, Long.class);
        assertNotNull(id);
        return id;
    }

    /** Chỉ chấp nhận SQLSTATE và tên constraint chính xác; lỗi kết nối/timeout không làm test xanh. */
    static void assertConstraint(Executable action, String state, String table, String constraint) {
        var failure = assertThrows(DataIntegrityViolationException.class, action);
        var postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals(state, postgres.getSQLState());
        var details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("identity", details.getSchema());
        assertEquals(table, details.getTable());
        assertEquals(constraint, details.getConstraint());
    }

    /** NOT NULL báo cột thay vì tên constraint; không chấp nhận CHECK khác làm test xanh. */
    static void assertNotNullColumn(Executable action, String table, String column) {
        var failure = assertThrows(DataIntegrityViolationException.class, action);
        var postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23502", postgres.getSQLState());
        var details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("identity", details.getSchema());
        assertEquals(table, details.getTable());
        assertEquals(column, details.getColumn());
    }
}
