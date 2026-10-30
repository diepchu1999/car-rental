package com.carrental.identity.adapter.out.persistence;

import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.domain.DocumentType;
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
import java.time.Instant;
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
    private final String lockReviewSql;
    private final String rejectSql;
    private final String pendingTypeSql;
    private final String registerCardSql;
    private final String verifySql;
    private final String activeSql;

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
        lockReviewSql = loader.load("sql/identity/lock_document_head_for_review.sql");
        rejectSql = loader.load("sql/identity/reject_pending_document.sql");
        pendingTypeSql = loader.load("sql/identity/find_pending_document_type_for_review.sql");
        registerCardSql = loader.load("sql/identity/register_verified_identity_card.sql");
        verifySql = loader.load("sql/identity/verify_pending_document.sql");
        activeSql = loader.load("sql/identity/update_document_head_verified.sql");
    }

    /** Cùng một savepoint cho đăng ký số, VERIFIED và active; lỗi bất kỳ không giữ chỗ số của người khác. */
    @Override public void verify(VerifyDocumentCommand command, String reviewerAccountCode, Instant reviewedAt) {
        Objects.requireNonNull(command, "command must not be null.");
        Objects.requireNonNull(reviewedAt, "reviewedAt must not be null.");
        if (reviewerAccountCode == null || !reviewerAccountCode.matches("TK-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("A valid reviewer account code is required.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Document review requires a caller transaction.");
        }
        nested.executeWithoutResult(status -> {
            var values = new MapSqlParameterSource().addValue("code", command.code(), Types.VARCHAR)
                    .addValue("reviewerAccountCode", reviewerAccountCode, Types.VARCHAR)
                    .addValue("reviewedAt", reviewedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            var heads = SensitiveSql.execute(() -> jdbc.queryForList(lockReviewSql, values, Long.class));
            if (heads.isEmpty()) throw DomainException.notFound(ErrorCode.DOCUMENT_NOT_FOUND);
            if (heads.size() != 1) throw new IllegalStateException("Expected one document head.");
            var types = SensitiveSql.execute(() -> jdbc.queryForList(pendingTypeSql, values, String.class));
            if (types.isEmpty()) throw DomainException.conflict(ErrorCode.DOCUMENT_REVIEW_CONFLICT);
            if (types.size() != 1) throw new IllegalStateException("Expected one pending document.");
            if (DocumentType.valueOf(types.getFirst()) == DocumentType.IDENTITY_CARD) {
                int registered = SensitiveSql.execute(() -> jdbc.update(registerCardSql, values));
                if (registered == 0) throw DomainException.conflict(ErrorCode.IDENTITY_CARD_ALREADY_REGISTERED);
                requireOne(registered);
            }
            requireOne(SensitiveSql.execute(() -> jdbc.update(verifySql, values)));
            requireOne(SensitiveSql.execute(() -> jdbc.update(activeSql, values)));
        });
    }

    /** Cùng thứ tự head rồi submission với insert; không đụng ảnh, lịch sử hay active VERIFIED. */
    @Override public void reject(RejectDocumentCommand command, String reviewerAccountCode, Instant reviewedAt) {
        Objects.requireNonNull(command, "command must not be null.");
        Objects.requireNonNull(reviewedAt, "reviewedAt must not be null.");
        if (reviewerAccountCode == null || !reviewerAccountCode.matches("TK-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("A valid reviewer account code is required.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Document review requires a caller transaction.");
        }
        nested.executeWithoutResult(status -> {
            var values = new MapSqlParameterSource()
                    .addValue("code", command.code(), Types.VARCHAR)
                    .addValue("reviewerAccountCode", reviewerAccountCode, Types.VARCHAR)
                    .addValue("reviewedAt", reviewedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("rejectionCode", command.rejectionCode().name(), Types.VARCHAR)
                    .addValue("rejectionNote", command.rejectionNote(), Types.VARCHAR);
            var heads = SensitiveSql.execute(() -> jdbc.queryForList(lockReviewSql, values, Long.class));
            if (heads.isEmpty()) throw DomainException.notFound(ErrorCode.DOCUMENT_NOT_FOUND);
            if (heads.size() != 1) throw new IllegalStateException("Expected one document head.");
            // Câu lệnh mới sau khi lấy khoá đọc trạng thái đã commit của bên thắng ở READ COMMITTED.
            int changed = SensitiveSql.execute(() -> jdbc.update(rejectSql, values));
            if (changed == 0) throw DomainException.conflict(ErrorCode.DOCUMENT_REVIEW_CONFLICT);
            requireOne(changed);
        });
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
