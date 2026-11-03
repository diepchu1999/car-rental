package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.identity.application.view.DocumentStatusDetail;
import java.time.Instant;
import java.time.LocalDate;

/** BR-130: kết quả quyết định chỉ gồm metadata, không chứa ID số, thông tin nhân thân hoặc ảnh. */
public record DocumentReviewResponse(String code, String documentType, String status, LocalDate expiresOn,
        Instant submittedAt, Instant reviewedAt, String rejectionCode, String rejectionNote, LocalDate issuedOn) {
    /** Map view application sang hợp đồng admin, không phụ thuộc DTO của audience client. */
    public static DocumentReviewResponse fromDomain(DocumentStatusDetail view) {
        return new DocumentReviewResponse(view.code(), view.documentType().name(), view.status().name(),
                view.expiresOn(), view.submittedAt(), view.reviewedAt(), view.rejectionCode(), view.rejectionNote(), view.issuedOn());
    }

    /** Ghi chú tự do không được lọt ra log khi DTO bị nối vào thông báo. */
    @Override public String toString() { return "DocumentReviewResponse[redacted]"; }
}
