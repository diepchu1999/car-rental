package com.carrental.identity.adapter.in.rest.client.response;

import com.carrental.identity.application.view.DocumentUploadDetail;
import java.time.Instant;

/** Response BR-130 chỉ có mã AN, loại/mặt và URL ngắn hạn; không có ID số hoặc trường object key riêng. */
public record DocumentUploadResponse(String code, String documentType, String side, String uploadUrl, Instant expiresAt) {
    /** Map read model sau khi use case đã hoàn tất transaction, không đọc SDK hoặc CSDL ở REST. */
    public static DocumentUploadResponse fromDomain(DocumentUploadDetail view) {
        return new DocumentUploadResponse(view.code(), view.documentType().name(), view.side().name(),
                view.uploadUrl(), view.expiresAt());
    }

    /** Không để record vô tình in URL ký ra log. */
    @Override public String toString() { return "DocumentUploadResponse[redacted]"; }
}
