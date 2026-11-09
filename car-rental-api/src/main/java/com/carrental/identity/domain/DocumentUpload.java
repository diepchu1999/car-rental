package com.carrental.identity.domain;

import java.time.Instant;

/**
 * Lượt tải ảnh BR-130 gắn với đúng chủ/loại/mặt; giữ object key, không giữ liên kết ký.
 * Mốc hết hạn lấy từ storage theo cấu hình, không tính lại khi đọc từ CSDL.
 */
public record DocumentUpload(String code, String accountCode, DocumentType documentType, DocumentSide side,
                             String incomingObjectKey, Instant createdAt, Instant expiresAt) {
    /** Kiểm cấu trúc bất biến, không lặp lại giá trị sai vào lỗi hoặc suy thêm quy tắc nghiệp vụ. */
    public DocumentUpload {
        if (code == null || !code.matches("AN-[A-Z0-9]{6}")
                || accountCode == null || !accountCode.matches("TK-[A-Z0-9]{6}")
                || documentType == null || side == null
                || incomingObjectKey == null || !incomingObjectKey.matches(
                "incoming/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Invalid document upload.");
        }
    }

    /** Không đưa khoá object hoặc chủ giấy tờ vào log khi caller vô ý in aggregate. */
    @Override
    public String toString() { return "DocumentUpload[REDACTED]"; }
}
