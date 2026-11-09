package com.carrental.identity.adapter.in.rest.admin.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.carrental.shared.error.DomainException;

/** BR-130: JSON thô chỉ có mã lý do và ghi chú tùy chọn; command kiểm giá trị nghiệp vụ. */
public record RejectDocumentRequest(String rejectionCode, String rejectionNote) {
    /** Không nhận chủ, reviewer, reviewedAt hoặc trạng thái từ client. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw DomainException.invalidInput("Unsupported document rejection field.");
    }

    /** Ghi chú có thể chứa dữ liệu nhạy cảm nên không dùng toString mặc định của record. */
    @Override public String toString() { return "RejectDocumentRequest[redacted]"; }
}
