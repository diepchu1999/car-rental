package com.carrental.identity.adapter.in.rest.client;

import com.carrental.identity.adapter.in.rest.client.request.SubmitDocumentRequest;
import com.carrental.identity.adapter.in.rest.client.response.DocumentSubmissionResponse;
import com.carrental.identity.adapter.in.rest.client.response.DocumentStatusResponse;
import com.carrental.identity.adapter.in.rest.client.response.DocumentHeadResponse;
import com.carrental.identity.application.port.in.GetDocumentStatusUseCase;
import com.carrental.identity.application.port.in.ListDocumentHeadsUseCase;
import com.carrental.identity.application.query.GetDocumentStatusQuery;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;

/** BR-120/130: biên nộp hồ sơ của khách, chỉ map dữ liệu; quyền và transaction thuộc use case. */
@RestController
@RequestMapping(path = "/api/v1/client/documents", produces = MediaType.APPLICATION_JSON_VALUE)
class ClientDocumentController {
    private final SubmitDocumentUseCase submissions;
    private final GetDocumentStatusUseCase statuses;
    private final ListDocumentHeadsUseCase heads;

    /** Không tiêm caller, storage, crypto hoặc persistence vào controller. */
    ClientDocumentController(SubmitDocumentUseCase submissions, GetDocumentStatusUseCase statuses, ListDocumentHeadsUseCase heads) {
        this.submissions = submissions; this.statuses = statuses; this.heads = heads;
    }

    /** Trả 201 sau commit, không cache; kèm ngày cấp GPLX để chủ kiểm lại dữ liệu đã nộp. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DocumentSubmissionResponse>> submit(@RequestBody SubmitDocumentRequest request) {
        var result = submissions.submit(SubmitDocumentCommand.from(request.documentType(), request.number(),
                request.fullName(), request.birthDate(), request.expiresOn(), request.licenseClass(),
                request.automaticOnly(), request.frontUploadCode(), request.backUploadCode(), request.issuedOn()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentSubmissionResponse.fromDomain(result)));
    }

    /** Danh sách tối đa một head mỗi loại, chỉ đọc của caller và không cache dữ liệu hồ sơ. */
    @GetMapping
    ResponseEntity<ApiResponse<List<DocumentHeadResponse>>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(heads.list().stream().map(DocumentHeadResponse::fromDomain).toList()));
    }

    /** Query tự kiểm GT; use case chịu trách nhiệm quyền và chống đọc chéo tài khoản. */
    @GetMapping("/{documentCode}")
    ResponseEntity<ApiResponse<DocumentStatusResponse>> get(@PathVariable String documentCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(DocumentStatusResponse.fromDomain(statuses.get(GetDocumentStatusQuery.from(documentCode)))));
    }
}
