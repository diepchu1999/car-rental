package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Objects;

/** Ghi lượt tải BR-130 bằng SQL có tham số; không để P6Spy/JDBC in khoá ảnh kể cả khi lỗi. */
@Repository
class DocumentUploadWriteAdapter implements WriteDocumentUploadPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String insertSql;

    /** Nạp SQL một lần; không thêm datasource hoặc ranh giới transaction riêng. */
    DocumentUploadWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.insertSql = loader.load("sql/identity/insert_document_upload.sql");
    }

    /** Chỉ uq_document_upload_code được bỏ qua; mọi lỗi khác ra ngoài dưới dạng đã che dữ liệu. */
    @Override
    public boolean insert(DocumentUpload upload) {
        Objects.requireNonNull(upload, "upload must not be null.");
        return SensitiveSql.execute(() -> {
            var parameters = new MapSqlParameterSource()
                    .addValue("code", upload.code(), Types.VARCHAR)
                    .addValue("accountCode", upload.accountCode(), Types.VARCHAR)
                    .addValue("documentType", upload.documentType().name(), Types.VARCHAR)
                    .addValue("side", upload.side().name(), Types.VARCHAR)
                    .addValue("objectKey", upload.incomingObjectKey(), Types.VARCHAR)
                    .addValue("createdAt", upload.createdAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("expiresAt", upload.expiresAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            int rows = jdbc.update(insertSql, parameters);
            if (rows != 0 && rows != 1) throw new IllegalStateException("Expected zero or one document upload row.");
            return rows == 1;
        });
    }
}
