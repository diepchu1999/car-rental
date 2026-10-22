package com.carrental.identity.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;

import java.time.Instant;

/**
 * Bản sao bằng chứng đồng ý điều khoản từ danh tính đã xác thực, BR-120 và ADR-0006.
 * Không đọc phiên bản cấu hình hiện tại, không tự điền thời gian máy chủ thay cho bằng chứng.
 *
 * @param version phiên bản khách đã đồng ý, giữ nguyên
 * @param consentedAt thời điểm đồng ý ở Keycloak
 */
public record TermsConsent(String version, Instant consentedAt) {

    /** Bằng chứng phải có cả hai thành phần; thiếu hoặc phiên bản trắng trả CONSENT_REQUIRED. */
    public TermsConsent {
        if (version == null || version.isBlank() || consentedAt == null) {
            throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);
        }
    }
}
