package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.domain.DocumentImage;
import com.carrental.identity.domain.DocumentSubmission;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Objects;

/** BR-130: ghi nguyên tử phiên bản và hai mặt ảnh, tuần tự hoá theo head, che toàn bộ JDBC nhạy cảm. */
@Repository
class DocumentSubmissionWriteAdapter implements WriteDocumentSubmissionPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate nested;
    private final String ensureHeadSql;
    private final String lockHeadSql;
    private final String supersedeSql;
    private final String insertSql;
    private final String imageSql;
    private final String latestSql;

    /** Savepoint không tự mở giao dịch nghiệp vụ; service vẫn sở hữu commit/rollback cuối cùng. */
    DocumentSubmissionWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader,
                                   PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        ensureHeadSql = loader.load("sql/identity/ensure_document_head.sql");
        lockHeadSql = loader.load("sql/identity/lock_document_head.sql");
        supersedeSql = loader.load("sql/identity/supersede_pending_document.sql");
        insertSql = loader.load("sql/identity/insert_document_submission.sql");
        imageSql = loader.load("sql/identity/insert_document_image.sql");
        latestSql = loader.load("sql/identity/update_document_head_latest.sql");
    }

    /** Trùng GT hoàn tác cả bước thay PENDING; lỗi ảnh/SQL cũng không để lại bản nộp dở dang. */
    @Override public boolean insert(DocumentSubmission submission) {
        Objects.requireNonNull(submission, "submission must not be null.");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Document submission requires a caller transaction.");
        }
        try {
            return Boolean.TRUE.equals(nested.execute(status -> SensitiveSql.execute(() -> {
                var values = parameters(submission);
                jdbc.update(ensureHeadSql, values);
                var heads = jdbc.queryForList(lockHeadSql, values, Long.class);
                if (heads.size() != 1) throw new IllegalStateException("Expected one document head.");
                int replaced = jdbc.update(supersedeSql, values);
                if (replaced > 1) throw new IllegalStateException("Multiple pending document submissions.");
                int inserted = jdbc.update(insertSql, values);
                if (inserted == 0) {
                    status.setRollbackOnly();
                    return false;
                }
                requireOne(inserted);
                insertImage(values, submission.frontImage());
                insertImage(values, submission.backImage());
                requireOne(jdbc.update(latestSql, values));
                return true;
            })));
        } catch (SensitiveSqlFailure failure) {
            if ("23505".equals(failure.sqlState()) && "uq_document_image_upload".equals(failure.constraint())) {
                throw DomainException.conflict(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED);
            }
            throw failure;
        }
    }

    /** Phong bì là dữ liệu duy nhất về số giấy tờ được bind; ngày/giờ dùng kiểu JDBC tường minh. */
    private static MapSqlParameterSource parameters(DocumentSubmission submission) {
        var number = submission.number();
        return new MapSqlParameterSource()
                .addValue("code", submission.code(), Types.VARCHAR)
                .addValue("accountCode", submission.accountCode(), Types.VARCHAR)
                .addValue("documentType", submission.documentType().name(), Types.VARCHAR)
                .addValue("keyVersion", number.keyVersion(), Types.INTEGER)
                .addValue("nonce", number.nonceBase64(), Types.VARCHAR)
                .addValue("ciphertext", number.ciphertextBase64(), Types.VARCHAR)
                .addValue("hash", number.lookupHash(), Types.VARCHAR)
                .addValue("fullName", submission.fullName(), Types.VARCHAR)
                .addValue("birthDate", submission.birthDate(), Types.DATE)
                .addValue("expiresOn", submission.expiresOn(), Types.DATE)
                .addValue("licenseClass", submission.licenseClass(), Types.VARCHAR)
                .addValue("automaticOnly", submission.automaticOnly(), Types.BOOLEAN)
                .addValue("submittedAt", submission.submittedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }

    /** Dùng cùng chủ/loại/mã GT; từng ảnh giữ AN và mặt đã được use case kiểm. */
    private void insertImage(MapSqlParameterSource values, DocumentImage image) {
        values.addValue("uploadCode", image.uploadCode(), Types.VARCHAR)
                .addValue("side", image.side().name(), Types.VARCHAR)
                .addValue("sealedKey", image.sealedObjectKey(), Types.VARCHAR)
                .addValue("contentType", image.contentType(), Types.VARCHAR)
                .addValue("sizeBytes", image.sizeBytes(), Types.BIGINT)
                .addValue("width", image.width(), Types.INTEGER)
                .addValue("height", image.height(), Types.INTEGER);
        requireOne(jdbc.update(imageSql, values));
    }

    /** Không biến thao tác ghi 0/nhiều dòng thành thành công im lặng. */
    private static void requireOne(int rows) {
        if (rows != 1) throw new IllegalStateException("Expected one affected document row.");
    }
}
