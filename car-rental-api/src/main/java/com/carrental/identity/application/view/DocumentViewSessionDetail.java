package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentNumber;
import com.carrental.storage.api.SignedImageRef;

import java.time.LocalDate;

/**
 * BR-130: thông tin chỉ dành cho người xem đã được kiểm quyền và ghi audit, không chứa phong bì/key ảnh.
 * Số rõ chỉ giữ trong bộ nhớ để đối chiếu; URL có hạn do storage quyết định từ cấu hình.
 */
public record DocumentViewSessionDetail(String accountCode, DocumentStatusDetail submission,
        DocumentNumber number, String fullName, LocalDate birthDate, String licenseClass,
        Boolean automaticOnly, SignedImageRef frontImage, SignedImageRef backImage) {
    /** Không để số rõ, nhân thân, ghi chú hoặc credential trong URL lọt qua log của record. */
    @Override public String toString() { return "DocumentViewSessionDetail[redacted]"; }
}
