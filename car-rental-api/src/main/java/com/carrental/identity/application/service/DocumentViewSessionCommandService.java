package com.carrental.identity.application.service;

import com.carrental.audit.api.AuditDirectory;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
import com.carrental.identity.application.port.in.OpenDocumentViewSessionUseCase;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentViewSessionDetail;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.storage.api.SignedImageRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** BR-130/803: chỉ người xác minh cấp công ty hoặc ADMIN được mở hồ sơ; không suy quyền từ chi nhánh. */
@Service
class DocumentViewSessionCommandService implements OpenDocumentViewSessionUseCase {
    private final CallerDirectory callers;
    private final ReadDocumentSubmissionPort documents;
    private final AuditDirectory audit;
    private final DocumentNumberCryptoPort crypto;
    private final DocumentStorageDirectory storage;
    private final Clock clock;

    /** Phụ thuộc các cổng công khai; application không đọc HTTP/MDC hay tự quản lý JDBC transaction. */
    DocumentViewSessionCommandService(CallerDirectory callers, ReadDocumentSubmissionPort documents,
            AuditDirectory audit, DocumentNumberCryptoPort crypto, DocumentStorageDirectory storage, Clock clock) {
        this.callers = callers; this.documents = documents; this.audit = audit;
        this.crypto = crypto; this.storage = storage; this.clock = clock;
    }

    /**
     * Không giữ transaction đọc ngoài trong lúc ký URL. AuditDirectory tự mở REQUIRES_NEW và commit
     * trước khi trả về; lỗi giải mã/ký sau đó không xoá dấu vết đã mở hồ sơ. Không retry/gộp theo requestId.
     */
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentViewSessionDetail open(OpenDocumentViewSessionCommand command) {
        var caller = callers.current();
        if (!caller.hasRole("ADMIN") && !caller.hasRole("DOCUMENT_VERIFIER")) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        Objects.requireNonNull(command, "command must not be null.");
        var document = documents.findForReviewByCode(command.code())
                .orElseThrow(() -> DomainException.notFound(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!command.code().equals(document.submission().code())) {
            throw new IllegalStateException("Document lookup is inconsistent.");
        }
        if (caller.accountCode().equals(document.accountCode())) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        audit.recordDocumentView(caller.accountCode(), document.accountCode(), command.code(), command.requestId());
        var number = crypto.reveal(document.number(), new DocumentProtectionContext(
                document.accountCode(), document.submission().documentType(), command.code()));
        if (number == null) throw new IllegalStateException("Document number could not be revealed.");
        var front = requireView(storage.createView(document.frontImage().sealedObjectKey()));
        var back = requireView(storage.createView(document.backImage().sealedObjectKey()));
        return new DocumentViewSessionDetail(document.accountCode(), document.submission(), number,
                document.fullName(), document.birthDate(), document.licenseClass(), document.automaticOnly(), front, back);
    }

    /** Cổng storage lỗi không được biến thành response thành công thiếu URL hoặc có URL hết hạn. */
    private SignedImageRef requireView(SignedImageRef image) {
        if (image == null || image.url() == null || image.url().isBlank()
                || image.expiresAt() == null || !image.expiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("Document storage returned no usable view link.");
        }
        return image;
    }
}
