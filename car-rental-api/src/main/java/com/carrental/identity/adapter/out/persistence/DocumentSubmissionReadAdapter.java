package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentReviewDetail;
import com.carrental.identity.application.view.PendingDocumentListItem;
import com.carrental.identity.domain.DocumentImage;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.ProtectedDocumentNumber;
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

/** Đọc bản nộp theo từng mục đích; che cả truy vấn và lỗi đọc row theo security-guideline §3. */
@Repository
class DocumentSubmissionReadAdapter implements ReadDocumentSubmissionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String findSql;
    private final String statusSql;
    private final String headsSql;
    private final String reviewSql;
    private final String pendingSql;
    private final String reviewStatusSql;

    /** Nạp SQL cố định, không nối input thành câu truy vấn. */
    DocumentSubmissionReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        findSql = loader.load("sql/identity/find_document_submission_by_code_and_owner.sql");
        statusSql = loader.load("sql/identity/find_document_status_by_code_and_owner.sql");
        headsSql = loader.load("sql/identity/list_document_heads_by_owner.sql");
        reviewSql = loader.load("sql/identity/find_document_submission_for_review.sql");
        pendingSql = loader.load("sql/identity/search_pending_documents.sql");
        reviewStatusSql = loader.load("sql/identity/find_document_status_for_review.sql");
    }

    /** Reload quyết định đúng GT; SensitiveSql che cả ghi chú tự do khi JDBC ghi log hoặc ném lỗi. */
    @Override public Optional<DocumentStatusDetail> findStatusForReviewByCode(String code) {
        return SensitiveSql.execute(() -> DataAccessUtils.optionalResult(jdbc.query(reviewStatusSql,
                Map.of("code", code), (row, ignored) -> status(row, ""))));
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

    /** Một câu SQL giữ bản nộp và hai ảnh trong cùng snapshot; chỉ đọc phong bì, không giải mã số. */
    @Override public Optional<DocumentReviewDetail> findForReviewByCode(String code) {
        return SensitiveSql.execute(() -> DataAccessUtils.optionalResult(jdbc.query(reviewSql,
                Map.of("code", code), (row, ignored) -> new DocumentReviewDetail(
                        row.getString("account_code"), status(row, ""),
                        new ProtectedDocumentNumber(row.getInt("number_key_version"),
                                row.getString("number_nonce"), row.getString("number_ciphertext"),
                                row.getString("number_hash")),
                        row.getString("full_name"), row.getObject("birth_date", LocalDate.class),
                        row.getString("license_class"), row.getObject("automatic_only", Boolean.class),
                        image(row, "front_", DocumentSide.FRONT), image(row, "back_", DocumentSide.BACK)))));
    }

    /** Giới hạn ngay tại PostgreSQL; SELECT chỉ bốn trường metadata, SensitiveSql che cả tham số và lỗi JDBC. */
    @Override public List<PendingDocumentListItem> searchPending(int fetchLimit, long offset) {
        if (fetchLimit < 1 || fetchLimit > 101 || offset < 0) {
            throw new IllegalArgumentException("Invalid pending document page bounds.");
        }
        return SensitiveSql.execute(() -> List.copyOf(jdbc.query(pendingSql,
                Map.of("fetchLimit", fetchLimit, "offset", offset),
                (row, ignored) -> new PendingDocumentListItem(row.getString("code"), row.getString("account_code"),
                        DocumentType.valueOf(row.getString("document_type")),
                        row.getObject("submitted_at", OffsetDateTime.class).toInstant()))));
    }

    /** Prefix do adapter chọn; LEFT JOIN giữ hàng lỗi để thiếu ảnh không bị hiểu nhầm thành không tồn tại. */
    private static DocumentImage image(ResultSet row, String prefix, DocumentSide side) throws SQLException {
        if (row.getString(prefix + "upload_code") == null) {
            throw new IllegalStateException("Document submission is missing a sealed image.");
        }
        return new DocumentImage(row.getString(prefix + "upload_code"), side,
                row.getString(prefix + "object_key"), row.getString(prefix + "content_type"),
                row.getLong(prefix + "size_bytes"), row.getInt(prefix + "width"), row.getInt(prefix + "height"));
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
