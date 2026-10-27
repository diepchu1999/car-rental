package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.port.in.GetDocumentStatusUseCase;
import com.carrental.identity.application.port.in.ListDocumentHeadsUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.query.GetDocumentStatusQuery;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** BR-120/130: quyền đọc nằm ở use case; chỉ đọc metadata của chủ, không giải mã hoặc ký URL ảnh. */
@Service
class DocumentStatusQueryService implements GetDocumentStatusUseCase, ListDocumentHeadsUseCase {
    private final CallerDirectory callers;
    private final ReadAccountPort accounts;
    private final ReadDocumentSubmissionPort documents;

    /** Nhận cổng đọc và caller đã xác thực; không phụ thuộc HTTP, JDBC, storage hoặc write port. */
    DocumentStatusQueryService(CallerDirectory callers, ReadAccountPort accounts, ReadDocumentSubmissionPort documents) {
        this.callers = callers; this.accounts = accounts; this.documents = documents;
    }

    /** Kiểm quyền trước tra cứu; SQL áp cả mã và chủ, giữ cùng lỗi 404 khi sai chủ hoặc thiếu mã. */
    @Override @Transactional(readOnly = true)
    public DocumentStatusDetail get(GetDocumentStatusQuery query) {
        String owner = requireCustomer();
        Objects.requireNonNull(query, "query must not be null.");
        var result = documents.findStatusByCodeAndOwner(query.code(), owner)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!query.code().equals(result.code())) throw new IllegalStateException("Document lookup is inconsistent.");
        return result;
    }

    /** Không suy trạng thái đủ điều kiện thuê từ active; chỉ phản ánh hai con trỏ đã lưu. */
    @Override @Transactional(readOnly = true)
    public List<DocumentHeadSummary> list() {
        return List.copyOf(documents.listHeadsByOwner(requireCustomer()));
    }

    /** BR-120/809: nhân viên có CUSTOMER chỉ đọc chính mình; ngoài request giữ lỗi rõ của caller. */
    private String requireCustomer() {
        var caller = callers.current();
        if (!caller.hasRole("CUSTOMER")) throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        String owner = caller.accountCode();
        var account = accounts.findByCode(owner)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND));
        if (!owner.equals(account.code())) throw new IllegalStateException("Account lookup is inconsistent.");
        if (account.consent().isEmpty()) throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);
        return owner;
    }
}
