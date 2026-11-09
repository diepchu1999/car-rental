package com.carrental.identity.application.service;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.application.port.in.SearchPendingDocumentsUseCase;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.query.SearchPendingDocumentsQuery;
import com.carrental.identity.application.view.PendingDocumentsPage;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** BR-130/803: chỉ ADMIN/verifier xem hàng chờ; không suy quyền từ chi nhánh hoặc việc là chủ hồ sơ. */
@Service
class DocumentQueueQueryService implements SearchPendingDocumentsUseCase {
    private final CallerDirectory callers;
    private final ReadDocumentSubmissionPort documents;

    /** Chỉ phụ thuộc caller và read port, không có cổng ghi, crypto, audit hoặc storage. */
    DocumentQueueQueryService(CallerDirectory callers, ReadDocumentSubmissionPort documents) {
        this.callers = callers; this.documents = documents;
    }

    /**
     * Đọc thêm đúng một hàng để tính hasMore trong cùng snapshot, không tải hết hàng chờ hoặc COUNT riêng.
     * Đây chưa phải mở chi tiết kèm ảnh; audit mở hồ sơ thuộc use case view-sessions theo BR-130.
     */
    @Override @Transactional(readOnly = true)
    public PendingDocumentsPage search(SearchPendingDocumentsQuery query) {
        var caller = callers.current();
        if (!caller.hasRole("ADMIN") && !caller.hasRole("DOCUMENT_VERIFIER")) {
            throw DomainException.forbidden(ErrorCode.ACCESS_DENIED);
        }
        Objects.requireNonNull(query, "query must not be null.");
        var rows = documents.searchPending(query.limit() + 1, query.offset());
        return new PendingDocumentsPage(rows.subList(0, Math.min(rows.size(), query.limit())), rows.size() > query.limit());
    }
}
