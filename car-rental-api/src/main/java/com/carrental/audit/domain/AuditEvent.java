package com.carrental.audit.domain;

import java.time.Instant;
import java.util.Objects;

/** Bản ghi bất biến BR-130; không chứa thông tin cá nhân hoặc vị trí object trong kho ảnh. */
public record AuditEvent(String actorAccountCode, String subjectAccountCode, String resourceCode,
                         Instant occurredAt, String requestId) {
    /** Kiểm lại hợp đồng domain, độc lập framework và command. */
    public AuditEvent {
        require(actorAccountCode, "TK-[A-Z0-9]{6}");
        require(subjectAccountCode, "TK-[A-Z0-9]{6}");
        require(resourceCode, "GT-[A-Z0-9]{6}");
        require(requestId, "[A-Za-z0-9-]{1,64}");
        Objects.requireNonNull(occurredAt, "occurredAt is required.");
    }

    /** Chặn giá trị sai mà không đưa chúng vào exception. */
    private static void require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) throw new IllegalArgumentException("Invalid audit identifier.");
    }

    /** Hành động duy nhất ở lát cắt hiện tại, không nhận chuỗi hành động do HTTP tự khai. */
    public String action() { return "DOCUMENT_VIEW_OPENED"; }
}
