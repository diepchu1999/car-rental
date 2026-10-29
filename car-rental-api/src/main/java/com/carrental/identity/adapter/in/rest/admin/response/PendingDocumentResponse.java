package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.identity.application.view.PendingDocumentListItem;
import java.time.Instant;

/** BR-130: một hàng metadata của hồ sơ chờ, không đưa ID số hoặc nội dung giấy tờ ra response. */
public record PendingDocumentResponse(String code, String accountCode, String documentType, Instant submittedAt) {
    /** Ánh xạ tường minh bốn trường; không serialize trực tiếp read model của application. */
    public static PendingDocumentResponse fromDomain(PendingDocumentListItem item) {
        return new PendingDocumentResponse(item.code(), item.accountCode(), item.documentType().name(), item.submittedAt());
    }

    /** Không ghi liên kết GT/TK khi logger nhận DTO. */
    @Override public String toString() { return "PendingDocumentResponse[redacted]"; }
}
