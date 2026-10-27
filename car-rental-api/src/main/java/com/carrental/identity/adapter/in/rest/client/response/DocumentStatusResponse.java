package com.carrental.identity.adapter.in.rest.client.response;

import com.carrental.identity.application.view.DocumentStatusDetail;
import java.time.Instant;
import java.time.LocalDate;

/** BR-130: metadata theo dõi và lý do từ chối của chủ; không có số giấy tờ, ID hoặc ảnh. */
public record DocumentStatusResponse(String code, String documentType, String status, LocalDate expiresOn,
        Instant submittedAt, Instant reviewedAt, String rejectionCode, String rejectionNote) {
    /** Map view thành hợp đồng API, không trả row CSDL hoặc enum nội bộ trực tiếp. */
    public static DocumentStatusResponse fromDomain(DocumentStatusDetail view) {
        return new DocumentStatusResponse(view.code(), view.documentType().name(), view.status().name(),
                view.expiresOn(), view.submittedAt(), view.reviewedAt(), view.rejectionCode(), view.rejectionNote());
    }

    /** Ghi chú tự do có thể chứa dữ liệu cá nhân, luôn che khi in DTO. */
    @Override public String toString() { return "DocumentStatusResponse[redacted]"; }
}
