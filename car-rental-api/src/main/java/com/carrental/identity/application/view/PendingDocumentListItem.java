package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentType;
import java.time.Instant;

/** BR-130: metadata tối thiểu của một bản PENDING, không có nhân thân, số giấy tờ hoặc ảnh. */
public record PendingDocumentListItem(String code, String accountCode, DocumentType documentType, Instant submittedAt) {
    /** Không đưa liên kết tài khoản–hồ sơ vào log khi logger nhận cả item. */
    @Override public String toString() { return "PendingDocumentListItem[redacted]"; }
}
