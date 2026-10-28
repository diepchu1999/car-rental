package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.storage.api.SignedImageRef;
import java.time.Instant;

/** BR-130: liên kết ảnh đã ký và hạn riêng, không trả thêm object key hoặc ID ảnh. */
public record DocumentViewImageResponse(String url, Instant expiresAt) {
    /** Chỉ chọn hai trường công khai; không trả trực tiếp kiểu cổng storage qua HTTP. */
    public static DocumentViewImageResponse fromDomain(SignedImageRef view) {
        return new DocumentViewImageResponse(view.url(), view.expiresAt());
    }

    /** URL là credential tạm thời, không được ghi vào log. */
    @Override public String toString() { return "DocumentViewImageResponse[redacted]"; }
}
