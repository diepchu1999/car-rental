package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Trạng thái một phiên bản BR-130 để chủ hồ sơ theo dõi, gồm lý do và ghi chú từ chối.
 * Không mang số giấy tờ, thông tin nhân thân, người duyệt, phong bì mật mã hoặc tham chiếu ảnh.
 */
public record DocumentStatusDetail(String code, DocumentType documentType, DocumentSubmissionStatus status,
        LocalDate expiresOn, Instant submittedAt, Instant reviewedAt, String rejectionCode, String rejectionNote, LocalDate issuedOn) {
    /** Ghi chú tự do có thể chứa dữ liệu cá nhân; không cho logger in cả view. */
    @Override public String toString() { return "DocumentStatusDetail[redacted]"; }
}
