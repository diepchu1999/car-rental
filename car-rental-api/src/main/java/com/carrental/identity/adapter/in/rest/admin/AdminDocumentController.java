package com.carrental.identity.adapter.in.rest.admin;

import com.carrental.identity.adapter.in.rest.admin.request.OpenDocumentViewSessionRequest;
import com.carrental.identity.adapter.in.rest.admin.request.VerifyDocumentRequest;
import com.carrental.identity.adapter.in.rest.admin.request.RejectDocumentRequest;
import com.carrental.identity.adapter.in.rest.admin.response.DocumentReviewResponse;
import com.carrental.identity.adapter.in.rest.admin.response.DocumentViewSessionResponse;
import com.carrental.identity.adapter.in.rest.admin.response.PendingDocumentsResponse;
import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.port.in.VerifyDocumentUseCase;
import com.carrental.identity.application.port.in.RejectDocumentUseCase;
import com.carrental.identity.application.port.in.OpenDocumentViewSessionUseCase;
import com.carrental.identity.application.port.in.SearchPendingDocumentsUseCase;
import com.carrental.identity.application.query.SearchPendingDocumentsQuery;
import com.carrental.shared.api.ApiResponse;
import com.carrental.shared.logging.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BR-130: biên HTTP hàng chờ, mở và quyết định giấy tờ; quyền/transaction do use case điều phối. */
@RestController
@RequestMapping(path = "/api/v1/admin/documents", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminDocumentController {
    private final OpenDocumentViewSessionUseCase sessions;
    private final SearchPendingDocumentsUseCase queue;
    private final VerifyDocumentUseCase verification;
    private final RejectDocumentUseCase rejection;

    /** Không tiêm persistence, crypto, audit hoặc storage vào controller. */
    AdminDocumentController(OpenDocumentViewSessionUseCase sessions, SearchPendingDocumentsUseCase queue,
            VerifyDocumentUseCase verification, RejectDocumentUseCase rejection) {
        this.sessions = sessions; this.queue = queue;
        this.verification = verification; this.rejection = rejection;
    }

    /** Không body hoặc {} đều hợp lệ; không nhận reviewer/time và không tự mở ảnh để xác minh. */
    @PostMapping("/{documentCode}/verify")
    ResponseEntity<ApiResponse<DocumentReviewResponse>> verify(@PathVariable String documentCode,
            @RequestBody(required = false) VerifyDocumentRequest request) {
        var result = verification.verify(VerifyDocumentCommand.from(documentCode));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentReviewResponse.fromDomain(result)));
    }

    /** Chỉ map hai trường sang command; lý do và ghi chú được kiểm ở application trước transaction ghi. */
    @PostMapping(path = "/{documentCode}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DocumentReviewResponse>> reject(@PathVariable String documentCode,
            @RequestBody RejectDocumentRequest request) {
        var result = rejection.reject(RejectDocumentCommand.from(documentCode,
                request.rejectionCode(), request.rejectionNote()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentReviewResponse.fromDomain(result)));
    }

    /** Chỉ ánh xạ tham số và metadata; đọc hàng chờ không mở ảnh hoặc tạo lượt xem giấy tờ. */
    @GetMapping
    ResponseEntity<ApiResponse<PendingDocumentsResponse>> search(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        var result = queue.search(SearchPendingDocumentsQuery.from(limit, offset));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(PendingDocumentsResponse.fromDomain(result)));
    }

    /** Mỗi POST tạo một lượt mở; ID truy vết lấy từ filter, không đọc lại header thô hoặc body. */
    @PostMapping("/{documentCode}/view-sessions")
    ResponseEntity<ApiResponse<DocumentViewSessionResponse>> open(@PathVariable String documentCode,
            @RequestBody(required = false) OpenDocumentViewSessionRequest request) {
        var result = sessions.open(OpenDocumentViewSessionCommand.from(documentCode, MDC.get(RequestIdFilter.MDC_KEY)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentViewSessionResponse.fromDomain(result)));
    }
}
