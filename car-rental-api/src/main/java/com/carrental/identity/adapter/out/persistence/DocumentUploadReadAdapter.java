package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/** Đọc có điều kiện chủ ngay trong SQL; che cả lỗi đọc row vì có thể chứa giá trị CSDL. */
@Repository
class DocumentUploadReadAdapter implements ReadDocumentUploadPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String findSql;

    /** Nạp truy vấn cố định, không nối mã khách cung cấp thành SQL. */
    DocumentUploadReadAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.findSql = loader.load("sql/identity/find_document_upload_by_code_and_owner.sql");
    }

    /** Không trả object key nếu mã lượt tải tồn tại nhưng không thuộc chủ được kiểm tại use case. */
    @Override
    public Optional<DocumentUpload> findByCodeAndOwner(String code, String accountCode) {
        return SensitiveSql.execute(() -> DataAccessUtils.optionalResult(jdbc.query(findSql,
                Map.of("code", code, "accountCode", accountCode), (row, ignored) -> new DocumentUpload(
                        row.getString("code"), row.getString("account_code"),
                        DocumentType.valueOf(row.getString("document_type")),
                        DocumentSide.valueOf(row.getString("side")), row.getString("incoming_object_key"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))));
    }
}
