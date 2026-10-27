package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;

import java.time.Instant;
import java.time.LocalDate;

/** Kết quả nộp BR-130 đã đọc lại, không mang số, phong bì mật mã, ID số, tên hoặc tham chiếu ảnh. */
public record DocumentSubmissionDetail(String code, DocumentType documentType,
        DocumentSubmissionStatus status, LocalDate expiresOn, Instant submittedAt) {
    /** Không đưa dữ liệu hồ sơ vào log khi logger vô tình nhận cả view. */
    @Override public String toString() { return "DocumentSubmissionDetail[redacted]"; }
}
