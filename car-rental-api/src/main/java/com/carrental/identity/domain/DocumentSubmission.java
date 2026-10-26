package com.carrental.identity.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * BR-130: dữ liệu bất biến của một lần nộp mới, số đã bảo vệ và đủ hai mặt ảnh đã chốt.
 * Không chứa số rõ hoặc kết quả duyệt; adapter chỉ được ghi bản này ở trạng thái PENDING.
 */
public record DocumentSubmission(String code, String accountCode, DocumentType documentType,
        ProtectedDocumentNumber number, String fullName, LocalDate birthDate, LocalDate expiresOn,
        String licenseClass, Boolean automaticOnly, Instant submittedAt,
        DocumentImage frontImage, DocumentImage backImage) {
    /** Ép đủ dữ liệu ở cửa ghi, không cho gọi port với một ảnh hoặc bộ trường sai loại. */
    public DocumentSubmission {
        if (code == null || !code.matches("GT-[A-Z0-9]{6}")
                || accountCode == null || !accountCode.matches("TK-[A-Z0-9]{6}")
                || documentType == null || number == null || expiresOn == null || submittedAt == null
                || frontImage == null || backImage == null
                || frontImage.side() != DocumentSide.FRONT || backImage.side() != DocumentSide.BACK
                || frontImage.uploadCode().equals(backImage.uploadCode())
                || frontImage.sealedObjectKey().equals(backImage.sealedObjectKey())) {
            throw new IllegalArgumentException("Invalid document submission.");
        }
        boolean validFields = switch (documentType) {
            case IDENTITY_CARD -> fullName != null && !fullName.isBlank() && birthDate != null
                    && licenseClass == null && automaticOnly == null;
            case DRIVER_LICENSE -> fullName == null && birthDate == null
                    && licenseClass != null && !licenseClass.isBlank() && automaticOnly != null;
        };
        if (!validFields) throw new IllegalArgumentException("Invalid document submission fields.");
    }

    /** Không để tên/ngày sinh, phong bì số hoặc ảnh xuất hiện trong log. */
    @Override public String toString() { return "DocumentSubmission[redacted]"; }
}
