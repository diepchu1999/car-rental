package com.carrental.identity.adapter.in.rest.client;

import com.carrental.identity.adapter.in.rest.client.request.CreateDocumentUploadRequest;
import com.carrental.identity.adapter.in.rest.client.response.DocumentUploadResponse;
import com.carrental.identity.application.command.CreateDocumentUploadCommand;
import com.carrental.identity.application.port.in.CreateDocumentUploadUseCase;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BR-130: REST chỉ map request/response; kiểm quyền, lưu chủ và gọi storage thuộc use case. */
@RestController
@RequestMapping(path = "/api/v1/client/document-uploads", produces = MediaType.APPLICATION_JSON_VALUE)
class ClientDocumentUploadController {
    private final CreateDocumentUploadUseCase uploads;

    /** Chỉ tiêm port/in, không nhận CallerDirectory, JDBC hoặc SDK MinIO. */
    ClientDocumentUploadController(CreateDocumentUploadUseCase uploads) { this.uploads = uploads; }

    /** URL ký không được cache; không đặt Location tới một endpoint GET chưa tồn tại. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DocumentUploadResponse>> create(@RequestBody CreateDocumentUploadRequest request) {
        var view = uploads.create(CreateDocumentUploadCommand.from(request.documentType(), request.side()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentUploadResponse.fromDomain(view)));
    }
}
