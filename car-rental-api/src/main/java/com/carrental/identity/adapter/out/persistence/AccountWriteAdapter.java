package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * BR-120: ghi trước, để UNIQUE phân xử rồi phân loại xung đột bằng lần đọc sau tại READ_COMMITTED.
 * Không bắt mọi lỗi JDBC thành trùng mã. Bảng account không có ràng buộc loại trừ;
 * cách DO NOTHING này không được sao chép sang availability.reservation.
 * Transaction thuộc application, không tự commit hoặc mở transaction độc lập.
 */
@Repository
class AccountWriteAdapter implements WriteAccountPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String insertSql;
    private final String conflictsSql;
    private final String consentSql;

    /** Tải ba câu SQL có tham số khi dựng adapter. */
    AccountWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.insertSql = loader.load("sql/identity/insert_account.sql");
        this.conflictsSql = loader.load("sql/identity/read_account_conflicts.sql");
        this.consentSql = loader.load("sql/identity/add_account_consent_if_absent.sql");
    }

    /** Chỉ CODE_COLLISION được thử mã khác; không đưa số điện thoại vào lỗi xung đột công khai. */
    @Override
    public InsertResult insert(Account account, Instant createdAt) {
        Objects.requireNonNull(createdAt, "createdAt must not be null.");
        var parameters = parameters(account).addValue("createdAt", createdAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        int rows = jdbc.update(insertSql, parameters);
        if (oneOrZero(rows)) return InsertResult.INSERTED;

        Conflicts conflicts = Objects.requireNonNull(jdbc.queryForObject(conflictsSql, parameters,
                (rs, row) -> new Conflicts(rs.getBoolean("subject_exists"), rs.getBoolean("phone_exists"), rs.getBoolean("code_exists"))));
        if (conflicts.subject()) return InsertResult.SUBJECT_EXISTS;
        if (conflicts.phone()) throw DomainException.conflict(ErrorCode.ACCOUNT_IDENTITY_CONFLICT);
        if (conflicts.code()) return InsertResult.CODE_COLLISION;
        throw new IllegalStateException("Account insert was skipped without a visible identity or code conflict.");
    }

    /** UPDATE có điều kiện giữ cặp bằng chứng nguyên vẹn, không trộn phiên bản và thời điểm hai lượt. */
    @Override
    public boolean addConsentIfAbsent(Account account) {
        Objects.requireNonNull(account, "account must not be null.");
        if (account.consent().isEmpty()) throw new IllegalArgumentException("Account consent is required for this write.");
        return oneOrZero(jdbc.update(consentSql, parameters(account)));
    }

    /** Truyền rõ kiểu của cả trường nullable và timestamp UTC cho PostgreSQL. */
    private static MapSqlParameterSource parameters(Account account) {
        Objects.requireNonNull(account, "account must not be null.");
        TermsConsent consent = account.consent().orElse(null);
        return new MapSqlParameterSource()
                .addValue("code", account.code(), Types.VARCHAR)
                .addValue("subject", account.keycloakSubject(), Types.VARCHAR)
                .addValue("phone", account.phoneNumber().value(), Types.VARCHAR)
                .addValue("version", consent == null ? null : consent.version(), Types.VARCHAR)
                .addValue("consentedAt", consent == null ? null : consent.consentedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** Chỉ chấp nhận 0/1 dòng; số dòng bất thường là lỗi hạ tầng, không giả thành xung đột. */
    private static boolean oneOrZero(int rows) {
        if (rows == 1) return true;
        if (rows == 0) return false;
        throw new IncorrectUpdateSemanticsDataAccessException("Expected zero or one account row, but got: " + rows);
    }

    /** Kết quả tra xung đột nội bộ adapter, không phải view trả ra API. */
    private record Conflicts(boolean subject, boolean phone, boolean code) { }
}
