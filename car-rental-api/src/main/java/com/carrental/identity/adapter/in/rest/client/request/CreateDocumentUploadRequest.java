package com.carrental.identity.adapter.in.rest.client.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.carrental.shared.error.DomainException;

/** Dữ liệu thô BR-130; không nhận chủ, URL, key hoặc thời hạn do client tự khai. */
public record CreateDocumentUploadRequest(String documentType, String side) {
    /** Từ chối trường ngoài hợp đồng, không âm thầm bỏ qua ý định cấp lượt tải cho tài khoản khác. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw DomainException.invalidInput("Unsupported document upload field.");
    }

    /** Không phản chiếu dữ liệu chưa validate vào log. */
    @Override public String toString() { return "CreateDocumentUploadRequest[redacted]"; }
}
