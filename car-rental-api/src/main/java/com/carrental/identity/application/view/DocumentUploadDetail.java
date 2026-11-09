package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import java.time.Instant;

/** Kết quả cấp lượt tải BR-130 cho chính khách; URL chỉ sống trong response, không lưu CSDL. */
public record DocumentUploadDetail(String code, DocumentType documentType, DocumentSide side,
                                   String uploadUrl, Instant expiresAt) {
    /** URL ký là bí mật ngắn hạn, không để record tự in ra log. */
    @Override public String toString() { return "DocumentUploadDetail[redacted]"; }
}
