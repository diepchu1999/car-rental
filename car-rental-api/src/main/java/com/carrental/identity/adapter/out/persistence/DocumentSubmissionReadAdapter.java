package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** Đọc lại bản nộp đúng chủ; che cả truy vấn và lỗi đọc row theo security-guideline §3. */
@Repository
class DocumentSubmissionReadAdapter implements ReadDocumentSubmissionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String findSql;

    /** Nạp SQL cố định, không nối input thành câu truy vấn. */
    DocumentSubmissionReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        findSql = loader.load("sql/identity/find_document_submission_by_code_and_owner.sql");
    }

    /** Không SELECT số đã mã hoá hoặc key ảnh khi chỉ cần trạng thái của bản nộp. */
    @Override public Optional<DocumentSubmissionDetail> findByCodeAndOwner(String code, String accountCode) {
        return SensitiveSql.execute(() -> DataAccessUtils.optionalResult(jdbc.query(findSql,
                Map.of("code", code, "accountCode", accountCode), (row, ignored) -> new DocumentSubmissionDetail(
                        row.getString("code"), DocumentType.valueOf(row.getString("document_type")),
                        DocumentSubmissionStatus.valueOf(row.getString("status")),
                        row.getObject("expires_on", LocalDate.class),
                        row.getObject("submitted_at", OffsetDateTime.class).toInstant()))));
    }
}
