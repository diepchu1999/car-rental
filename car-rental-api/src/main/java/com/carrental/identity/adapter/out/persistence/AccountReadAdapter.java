package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.domain.Account;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.shared.validation.Validations;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/** Tra tài khoản bằng Native SQL, giữ nguyên lỗi lưu trữ và chỉ trả rỗng khi thực sự không có. */
@Repository
class AccountReadAdapter implements ReadAccountPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String byCodeSql;
    private final String bySubjectSql;
    private final AccountRowMapper mapper = new AccountRowMapper();

    /** Tải SQL một lần, không đọc file trên mỗi request. */
    AccountReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.byCodeSql = loader.load("sql/identity/find_account_by_code.sql");
        this.bySubjectSql = loader.load("sql/identity/find_account_by_subject.sql");
    }

    /** Dùng tham số SQL, không nối mã vào câu truy vấn. */
    @Override
    public Optional<Account> findByCode(String code) {
        return DataAccessUtils.optionalResult(jdbc.query(byCodeSql,
                Map.of("code", Validations.requiredText(code, "code")), mapper));
    }

    /** Dùng subject đã xác thực để lấy đúng tài khoản, không fallback sang số điện thoại. */
    @Override
    public Optional<Account> findBySubject(String subject) {
        return DataAccessUtils.optionalResult(jdbc.query(bySubjectSql,
                Map.of("subject", Validations.requiredText(subject, "subject")), mapper));
    }
}
