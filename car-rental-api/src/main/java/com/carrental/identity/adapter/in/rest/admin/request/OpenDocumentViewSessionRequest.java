package com.carrental.identity.adapter.in.rest.admin.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.carrental.shared.error.DomainException;

/** BR-130: lượt mở không nhận trường body; cho phép {} để client gửi POST JSON rỗng. */
public record OpenDocumentViewSessionRequest() {
    /** Không để client tự chọn chủ, người xem, requestId hay object key bằng trường ngoài hợp đồng. */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw DomainException.invalidInput("Document view sessions do not accept body fields.");
    }
}
