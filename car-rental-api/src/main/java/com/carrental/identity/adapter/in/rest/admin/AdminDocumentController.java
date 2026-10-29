package com.carrental.identity.adapter.in.rest.admin;

import com.carrental.identity.adapter.in.rest.admin.request.OpenDocumentViewSessionRequest;
import com.carrental.identity.adapter.in.rest.admin.response.DocumentViewSessionResponse;
import com.carrental.identity.adapter.in.rest.admin.response.PendingDocumentsResponse;
import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
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

/** BR-130: biên HTTP hàng chờ và mở giấy tờ; quyền, audit và ký URL do use case điều phối. */
@RestController
@RequestMapping(path = "/api/v1/admin/documents", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminDocumentController {
    private final OpenDocumentViewSessionUseCase sessions;
    private final SearchPendingDocumentsUseCase queue;

    /** Không tiêm persistence, crypto, audit hoặc storage vào controller. */
    AdminDocumentController(OpenDocumentViewSessionUseCase sessions, SearchPendingDocumentsUseCase queue) {
        this.sessions = sessions; this.queue = queue;
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
