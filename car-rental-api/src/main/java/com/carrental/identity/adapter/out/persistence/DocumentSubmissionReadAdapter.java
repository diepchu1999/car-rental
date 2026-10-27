package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Đọc lại bản nộp đúng chủ; che cả truy vấn và lỗi đọc row theo security-guideline §3. */
@Repository
class DocumentSubmissionReadAdapter implements ReadDocumentSubmissionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String findSql;
    private final String statusSql;
    private final String headsSql;

    /** Nạp SQL cố định, không nối input thành câu truy vấn. */
    DocumentSubmissionReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        findSql = loader.load("sql/identity/find_document_submission_by_code_and_owner.sql");
        statusSql = loader.load("sql/identity/find_document_status_by_code_and_owner.sql");
        headsSql = loader.load("sql/identity/list_document_heads_by_owner.sql");
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

    /** Cùng chính sách không tiết lộ tồn tại cho mã sai chủ; che cả ghi chú khi JDBC lỗi. */
    @Override public Optional<DocumentStatusDetail> findStatusByCodeAndOwner(String code, String accountCode) {
        return SensitiveSql.execute(() -> DataAccessUtils.optionalResult(jdbc.query(statusSql,
                Map.of("code", code, "accountCode", accountCode), (row, ignored) -> status(row, ""))));
    }

    /** Một lần đọc giữ latest/active nhất quán; không giải mã số, không gọi storage hay tạo URL. */
    @Override public List<DocumentHeadSummary> listHeadsByOwner(String accountCode) {
        return SensitiveSql.execute(() -> List.copyOf(jdbc.query(headsSql, Map.of("accountCode", accountCode),
                (row, ignored) -> new DocumentHeadSummary(DocumentType.valueOf(row.getString("document_type")),
                        status(row, "latest_"), status(row, "active_")))));
    }

    /** Prefix cố định trong adapter; mã null chỉ biểu diễn bên active chưa được chọn của LEFT JOIN. */
    private static DocumentStatusDetail status(ResultSet row, String prefix) throws SQLException {
        String code = row.getString(prefix + "code");
        if (code == null) return null;
        var reviewedAt = row.getObject(prefix + "reviewed_at", OffsetDateTime.class);
        return new DocumentStatusDetail(code, DocumentType.valueOf(row.getString(prefix + "document_type")),
                DocumentSubmissionStatus.valueOf(row.getString(prefix + "status")),
                row.getObject(prefix + "expires_on", LocalDate.class),
                row.getObject(prefix + "submitted_at", OffsetDateTime.class).toInstant(),
                reviewedAt == null ? null : reviewedAt.toInstant(),
                row.getString(prefix + "rejection_code"), row.getString(prefix + "rejection_note"));
    }
}
