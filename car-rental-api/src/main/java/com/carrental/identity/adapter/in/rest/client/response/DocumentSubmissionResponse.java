package com.carrental.identity.adapter.in.rest.client.response;

import com.carrental.identity.application.view.DocumentSubmissionDetail;
import java.time.Instant;
import java.time.LocalDate;

/** Kết quả nộp BR-130; chỉ mã nghiệp vụ và metadata, không mang số, tên, ngày sinh hoặc tham chiếu ảnh. */
public record DocumentSubmissionResponse(String code, String documentType, String status,
        LocalDate expiresOn, Instant submittedAt) {
    /** Map view đã reload sau transaction; không tự gán trạng thái hoặc thời điểm ở REST. */
    public static DocumentSubmissionResponse fromDomain(DocumentSubmissionDetail view) {
        return new DocumentSubmissionResponse(view.code(), view.documentType().name(), view.status().name(),
                view.expiresOn(), view.submittedAt());
    }

    /** Che metadata hồ sơ nếu logger vô tình nhận response. */
    @Override public String toString() { return "DocumentSubmissionResponse[redacted]"; }
}
