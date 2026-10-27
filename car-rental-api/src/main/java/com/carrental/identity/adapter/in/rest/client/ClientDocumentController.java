package com.carrental.identity.adapter.in.rest.client;

import com.carrental.identity.adapter.in.rest.client.request.SubmitDocumentRequest;
import com.carrental.identity.adapter.in.rest.client.response.DocumentSubmissionResponse;
import com.carrental.identity.application.command.SubmitDocumentCommand;
import com.carrental.identity.application.port.in.SubmitDocumentUseCase;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BR-120/130: biên nộp hồ sơ của khách, chỉ map dữ liệu; quyền và transaction thuộc use case. */
@RestController
@RequestMapping(path = "/api/v1/client/documents", produces = MediaType.APPLICATION_JSON_VALUE)
class ClientDocumentController {
    private final SubmitDocumentUseCase submissions;

    /** Không tiêm caller, storage, crypto hoặc persistence vào controller. */
    ClientDocumentController(SubmitDocumentUseCase submissions) { this.submissions = submissions; }

    /** Trả 201 sau commit, không cache; chưa đặt Location tới GET theo GT khi GET chưa được hiện thực. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DocumentSubmissionResponse>> submit(@RequestBody SubmitDocumentRequest request) {
        var result = submissions.submit(SubmitDocumentCommand.from(request.documentType(), request.number(),
                request.fullName(), request.birthDate(), request.expiresOn(), request.licenseClass(),
                request.automaticOnly(), request.frontUploadCode(), request.backUploadCode()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentSubmissionResponse.fromDomain(result)));
    }
}
