package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.application.port.in.RejectDocumentUseCase;
import com.carrental.identity.application.port.in.VerifyDocumentUseCase;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** BR-130/803: quyết định cấp công ty; quyền kiểm ở use case, tranh chấp do adapter/CSDL phân xử. */
@Service
class DocumentReviewCommandService implements VerifyDocumentUseCase, RejectDocumentUseCase {
    private final CallerDirectory callers;
    private final WriteDocumentSubmissionPort writes;
    private final ReadDocumentSubmissionPort reads;
    private final Clock clock;

    /** Chỉ nhận port và Clock; không phụ thuộc HTTP, mật mã, storage hoặc audit mở ảnh. */
    DocumentReviewCommandService(CallerDirectory callers, WriteDocumentSubmissionPort writes,
            ReadDocumentSubmissionPort reads, Clock clock) {
        this.callers = callers;
        this.writes = writes;
        this.reads = reads;
        this.clock = clock;
    }

    /** Claim số, VERIFIED và active cùng transaction; đọc lại lỗi thì hoàn tác cả quyết định. */
    @Override @Transactional
    public DocumentStatusDetail verify(VerifyDocumentCommand command) {
        String reviewer = requireReviewer();
        Objects.requireNonNull(command, "command must not be null.");
        requireDifferentOwner(command.code(), reviewer);
        var reviewedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        writes.verify(command, reviewer, reviewedAt);
        return reload(command.code(), DocumentSubmissionStatus.VERIFIED, reviewedAt, null, null);
    }

    /** Từ chối giữ nguyên ghi chú đã validate; không đọc-rồi-ghi để quyết định bản còn PENDING. */
    @Override @Transactional
    public DocumentStatusDetail reject(RejectDocumentCommand command) {
        String reviewer = requireReviewer();
        Objects.requireNonNull(command, "command must not be null.");
        requireDifferentOwner(command.code(), reviewer);
        var reviewedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        writes.reject(command, reviewer, reviewedAt);
        return reload(command.code(), DocumentSubmissionStatus.REJECTED, reviewedAt,
                command.rejectionCode().name(), command.rejectionNote());
    }

    /** Kiểm quyền trước mọi đọc/ghi; caller ngoài request tiếp tục ném lỗi rõ ràng từ CallerDirectory. */
    private String requireReviewer() {
        var caller = callers.current();
        if (!caller.hasRole("ADMIN") && !caller.hasRole("DOCUMENT_VERIFIER")) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        return caller.accountCode();
    }

    /** BR-130: ADMIN cũng không tự quyết định; chủ bất biến, trạng thái vẫn do câu ghi nguyên tử phân xử. */
    private void requireDifferentOwner(String code, String reviewer) {
        String owner = reads.findOwnerCodeByCode(code)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.DOCUMENT_NOT_FOUND));
        if (reviewer.equals(owner)) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
    }

    /** Chỉ đọc metadata kết quả; không mở ảnh, giải mã hoặc tự chuyển sang latest của một bản khác. */
    private DocumentStatusDetail reload(String code, DocumentSubmissionStatus status, Instant reviewedAt,
            String rejectionCode, String rejectionNote) {
        var stored = reads.findStatusForReviewByCode(code)
                .orElseThrow(() -> new IllegalStateException("Reviewed document could not be reloaded."));
        if (!code.equals(stored.code()) || stored.status() != status
                || !reviewedAt.equals(stored.reviewedAt())
                || !Objects.equals(rejectionCode, stored.rejectionCode())
                || !Objects.equals(rejectionNote, stored.rejectionNote())) {
            throw new IllegalStateException("Document review reload is inconsistent.");
        }
        return stored;
    }
}
