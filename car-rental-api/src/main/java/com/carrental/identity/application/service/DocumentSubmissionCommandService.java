package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.command.SubmitDocumentCommand;
import com.carrental.identity.application.port.in.SubmitDocumentUseCase;
import com.carrental.identity.application.port.out.DocumentNumberCryptoPort;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.domain.DocumentImage;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentSubmission;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.api.DocumentStorageDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** BR-120/130: nộp của chính khách, kiểm ảnh trước khi ghi và giữ nguyên bản VERIFIED đang dùng. */
@Service
class DocumentSubmissionCommandService implements SubmitDocumentUseCase {
    private static final int MAX_CODE_ATTEMPTS = 5;
    private final CallerDirectory callers;
    private final ReadAccountPort accounts;
    private final ReadDocumentUploadPort uploads;
    private final DocumentStorageDirectory storage;
    private final DocumentNumberCryptoPort crypto;
    private final WriteDocumentSubmissionPort writes;
    private final ReadDocumentSubmissionPort reads;
    private final BusinessCodeGenerator codes;
    private final Clock clock;

    /** Dùng port và storage.api; không mang SDK, JDBC hoặc HTTP vào tầng application. */
    DocumentSubmissionCommandService(CallerDirectory callers, ReadAccountPort accounts,
            ReadDocumentUploadPort uploads, DocumentStorageDirectory storage, DocumentNumberCryptoPort crypto,
            WriteDocumentSubmissionPort writes, ReadDocumentSubmissionPort reads,
            BusinessCodeGenerator codes, Clock clock) {
        this.callers = callers; this.accounts = accounts; this.uploads = uploads; this.storage = storage;
        this.crypto = crypto; this.writes = writes; this.reads = reads; this.codes = codes; this.clock = clock;
    }

    /**
     * Chỉ trùng GT mới thử tối đa năm mã; mỗi GT cần AAD mới, ảnh và mốc nộp không đổi khi thử lại.
     * Chốt ảnh không thuộc transaction PostgreSQL: rollback có thể để ảnh sealed mồ côi, dọn ở task sau.
     * Không xoá ảnh để bù transaction và không coi hạn URL PUT là hạn nộp hồ sơ.
     */
    @Override @Transactional
    public DocumentSubmissionDetail submit(SubmitDocumentCommand command) {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        Objects.requireNonNull(command, "command must not be null.");
        String owner = caller.accountCode();
        var account = accounts.findByCode(owner)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!owner.equals(account.code())) throw new IllegalStateException("Account lookup is inconsistent.");
        if (account.consent().isEmpty()) throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);

        // BR-128/130: dùng một mốc nộp duy nhất; chặn ngày cấp tương lai trước đọc/chốt ảnh.
        var submittedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (command.issuedOn() != null && command.issuedOn().isAfter(
                submittedAt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate())) {
            throw DomainException.invalidInput("issuedOn must not be after the submission date.");
        }
        // Kiểm xong cả hai tham chiếu trước khi đọc/chốt bất kỳ object nào.
        var frontUpload = requireUpload(command.frontUploadCode(), owner, command.documentType(), DocumentSide.FRONT);
        var backUpload = requireUpload(command.backUploadCode(), owner, command.documentType(), DocumentSide.BACK);
        var front = seal(frontUpload);
        var back = seal(backUpload);
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = codes.generate("GT");
            var number = crypto.protect(command.number(), new DocumentProtectionContext(owner, command.documentType(), code));
            var submission = new DocumentSubmission(code, owner, command.documentType(), number,
                    command.fullName(), command.birthDate(), command.expiresOn(), command.licenseClass(),
                    command.automaticOnly(), submittedAt, front, back, command.issuedOn());
            if (!writes.insert(submission)) continue;
            var stored = reads.findByCodeAndOwner(code, owner)
                    .orElseThrow(() -> new IllegalStateException("Created document submission could not be reloaded."));
            var expected = new DocumentSubmissionDetail(code, command.documentType(),
                    DocumentSubmissionStatus.PENDING, command.expiresOn(), submittedAt, command.issuedOn());
            if (!expected.equals(stored)) throw new IllegalStateException("Document submission reload is inconsistent.");
            return stored;
        }
        throw new IllegalStateException("Could not allocate a unique document submission code after five attempts.");
    }

    /** Sai chủ hoặc không tồn tại cùng trả 404; sai loại/mặt là input sai, không lặp lại AN/key trong lỗi. */
    private DocumentUpload requireUpload(String code, String owner, DocumentType type, DocumentSide side) {
        var upload = uploads.findByCodeAndOwner(code, owner)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.DOCUMENT_UPLOAD_NOT_FOUND));
        if (!code.equals(upload.code()) || !owner.equals(upload.accountCode())) {
            throw new IllegalStateException("Document upload lookup is inconsistent.");
        }
        if (type != upload.documentType() || side != upload.side()) {
            throw DomainException.invalidInput("Document uploads must match the requested document type and sides.");
        }
        return upload;
    }

    /** Storage tự kiểm bytes/loại/kích thước; application chỉ nhận metadata đã chốt, không nhận key từ client. */
    private DocumentImage seal(DocumentUpload upload) {
        var image = storage.sealUpload(upload.incomingObjectKey());
        if (image == null) throw new IllegalStateException("Document storage returned no sealed image.");
        return new DocumentImage(upload.code(), upload.side(), image.objectKey(), image.contentType(),
                image.sizeBytes(), image.width(), image.height());
    }
}
