package com.carrental.identity.adapter.in.rest.admin.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.carrental.shared.error.DomainException;

/** BR-130: xác minh chỉ nhận GT trên path; body không có trường nghiệp vụ. */
public record VerifyDocumentRequest() {
    /** Người duyệt và mốc duyệt luôn lấy từ server, không âm thầm bỏ qua trường tự khai. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw DomainException.invalidInput("Document verification does not accept body fields.");
    }
}
