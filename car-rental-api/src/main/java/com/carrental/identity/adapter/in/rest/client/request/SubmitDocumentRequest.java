package com.carrental.identity.adapter.in.rest.client.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.carrental.shared.error.DomainException;

/** BR-130: dữ liệu tự khai và hai AN; ngày để dạng thô cho command kiểm mà không lộ lỗi parser. */
public record SubmitDocumentRequest(String documentType, String number, String fullName, String birthDate,
        String expiresOn, String licenseClass, Boolean automaticOnly, String frontUploadCode, String backUploadCode) {
    /** Không nhận trường lạ như chủ, trạng thái, reviewer, object key hoặc URL ký từ client. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw DomainException.invalidInput("Unsupported document submission field.");
    }

    /** Toàn request chứa PII chưa kiểm chứng, không được in qua log. */
    @Override public String toString() { return "SubmitDocumentRequest[redacted]"; }
}
