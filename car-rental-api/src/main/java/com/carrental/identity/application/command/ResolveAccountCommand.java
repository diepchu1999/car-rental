package com.carrental.identity.application.command;

import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.validation.Validations;

import java.time.Instant;
import java.util.Set;

/**
 * Danh tính đã xác thực để tạo hoặc lấy tài khoản theo BR-120/809.
 * Chỉ biên xác thực được dựng command từ claims tin cậy, không nhận từ body do khách tự khai.
 * Command không tự xác minh token và không phụ thuộc HTTP hay Keycloak SDK.
 */
public record ResolveAccountCommand(String keycloakSubject, VietnamMobileNumber phoneNumber,
                                    AccountRoles roles, TermsConsent consent) {
    /** Ép định danh bắt buộc và consent của CUSTOMER trước bất kỳ thao tác lưu trữ nào. */
    public ResolveAccountCommand {
        keycloakSubject = Validations.requiredText(keycloakSubject, "keycloakSubject");
        phoneNumber = Validations.required(phoneNumber, "phoneNumber");
        roles = Validations.required(roles, "roles");
        if (roles.has(AccountRole.CUSTOMER) && consent == null) {
            throw DomainException.forbidden(ErrorCode.CONSENT_REQUIRED);
        }
    }

    /** Chuẩn hoá số di động VN và kiểm đủ cặp bằng chứng; không tự điền phiên bản hay thời điểm. */
    public static ResolveAccountCommand from(String subject, String phone, Set<AccountRole> roles,
                                             String termsVersion, Instant consentedAt) {
        TermsConsent consent = termsVersion == null && consentedAt == null
                ? null : new TermsConsent(termsVersion, consentedAt);
        return new ResolveAccountCommand(subject, new VietnamMobileNumber(phone), new AccountRoles(roles), consent);
    }

    /** Tránh lộ subject, số điện thoại và bằng chứng khi command vô tình được nối vào log. */
    @Override
    public String toString() { return "ResolveAccountCommand[redacted]"; }
}
