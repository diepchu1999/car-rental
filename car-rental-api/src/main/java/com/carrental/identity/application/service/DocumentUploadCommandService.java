package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.command.CreateDocumentUploadCommand;
import com.carrental.identity.application.port.in.CreateDocumentUploadUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.application.view.DocumentUploadDetail;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.time.temporal.ChronoUnit;

/** BR-120/130: kiểm khách trước khi ký, lưu và đọc lại lượt tải trong transaction của use case. */
@Service
class DocumentUploadCommandService implements CreateDocumentUploadUseCase {
    private static final int MAX_CODE_ATTEMPTS = 5;
    private final CallerDirectory callers;
    private final ReadAccountPort accounts;
    private final DocumentStorageDirectory storage;
    private final WriteDocumentUploadPort writes;
    private final ReadDocumentUploadPort reads;
    private final BusinessCodeGenerator codes;
    private final Clock clock;

    /** Chỉ phụ thuộc port và storage.api theo ADR-0008; không đưa JDBC/SDK/HTTP vào application. */
    DocumentUploadCommandService(CallerDirectory callers, ReadAccountPort accounts, DocumentStorageDirectory storage,
            WriteDocumentUploadPort writes, ReadDocumentUploadPort reads, BusinessCodeGenerator codes, Clock clock) {
        this.callers = callers; this.accounts = accounts; this.storage = storage;
        this.writes = writes; this.reads = reads; this.codes = codes; this.clock = clock;
    }

    /**
     * Trùng AN mới thử mã khác, dùng lại cùng key/hạn; lỗi lưu trữ không được giả thành trùng mã.
     * Chưa trả URL cho client nếu ghi/reload/commit lỗi. Ký URL không tạo object trong MinIO.
     */
    @Override
    @Transactional
    public DocumentUploadDetail create(CreateDocumentUploadCommand command) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Objects.requireNonNull(command, "command must not be null.");
        var account = accounts.findByCode(caller.accountCode())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!caller.accountCode().equals(account.code())) throw new IllegalStateException("Account lookup is inconsistent.");
        if (account.consent().isEmpty()) throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);

        var createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var signed = storage.createUpload();
        if (signed == null || signed.url() == null || signed.url().isBlank()) {
            throw new IllegalStateException("Document storage returned no upload link.");
        }
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            var upload = new DocumentUpload(codes.generate("AN"), caller.accountCode(), command.documentType(),
                    command.side(), signed.objectKey(), createdAt,
                    signed.expiresAt() == null ? null : signed.expiresAt().truncatedTo(ChronoUnit.MICROS));
            if (!writes.insert(upload)) continue;
            var stored = reads.findByCodeAndOwner(upload.code(), caller.accountCode())
                    .orElseThrow(() -> new IllegalStateException("Created document upload could not be reloaded."));
            // Chuẩn hoá về microsecond trước ghi để tránh PostgreSQL làm tròn nano khi reload.
            if (!stored.equals(upload)) {
                throw new IllegalStateException("Document upload reload is inconsistent.");
            }
            return new DocumentUploadDetail(stored.code(), stored.documentType(), stored.side(),
                    signed.url(), stored.expiresAt());
        }
        throw new IllegalStateException("Could not allocate a unique document upload code after five attempts.");
    }
}
