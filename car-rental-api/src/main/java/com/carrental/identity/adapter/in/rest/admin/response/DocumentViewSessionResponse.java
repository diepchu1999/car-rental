package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.identity.application.view.DocumentViewSessionDetail;
import java.time.Instant;
import java.time.LocalDate;

/** BR-130: dữ liệu đối chiếu chỉ trả sau kiểm quyền và audit, không có ID số hoặc phong bì mật mã. */
public record DocumentViewSessionResponse(String code, String accountCode, String documentType, String status,
        String number, String fullName, LocalDate birthDate, LocalDate expiresOn, String licenseClass,
        Boolean automaticOnly, Instant submittedAt, Instant reviewedAt, String rejectionCode, String rejectionNote,
        DocumentViewImageResponse frontImage, DocumentViewImageResponse backImage) {
    /** Map tường minh từ view, không trả row CSDL hoặc serialization của domain ra ngoài. */
    public static DocumentViewSessionResponse fromDomain(DocumentViewSessionDetail view) {
        var submission = view.submission();
        return new DocumentViewSessionResponse(submission.code(), view.accountCode(), submission.documentType().name(),
                submission.status().name(), view.number().value(), view.fullName(), view.birthDate(), submission.expiresOn(),
                view.licenseClass(), view.automaticOnly(), submission.submittedAt(), submission.reviewedAt(),
                submission.rejectionCode(), submission.rejectionNote(),
                DocumentViewImageResponse.fromDomain(view.frontImage()), DocumentViewImageResponse.fromDomain(view.backImage()));
    }

    /** Che toàn bộ số giấy tờ, nhân thân, ghi chú và URL khi logger vô tình nhận DTO. */
    @Override public String toString() { return "DocumentViewSessionResponse[redacted]"; }
}
