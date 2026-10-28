package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentImage;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.ProtectedDocumentNumber;

import java.time.LocalDate;

/**
 * BR-130: dữ liệu nội bộ của đúng một bản nộp phục vụ người xác minh, không phải response HTTP.
 * Số vẫn ở dạng mã hoá; ảnh chỉ là tham chiếu đã chốt. Use case phải kiểm quyền trước khi đọc,
 * ghi audit trước khi cung cấp thông tin và URL xem cho người gọi.
 */
public record DocumentReviewDetail(String accountCode, DocumentStatusDetail submission,
        ProtectedDocumentNumber number, String fullName, LocalDate birthDate,
        String licenseClass, Boolean automaticOnly, DocumentImage frontImage, DocumentImage backImage) {
    /** Không cho dữ liệu thiếu ảnh biến thành hồ sơ mở được hoặc âm thầm trả 404. */
    public DocumentReviewDetail {
        if (accountCode == null || !accountCode.matches("TK-[A-Z0-9]{6}")
                || submission == null || number == null || frontImage == null || backImage == null
                || frontImage.side() != DocumentSide.FRONT || backImage.side() != DocumentSide.BACK) {
            throw new IllegalArgumentException("Incomplete document review data.");
        }
    }

    /** Che cả thông tin nhân thân, ghi chú, phong bì số và tham chiếu ảnh khi ghi log. */
    @Override public String toString() { return "DocumentReviewDetail[redacted]"; }
}
