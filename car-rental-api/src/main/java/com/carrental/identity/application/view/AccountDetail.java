package com.carrental.identity.application.view;

import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;

import java.time.Instant;

/** Dữ liệu tài khoản đọc lại từ CSDL; không mang ID số, token hoặc vai được suy từ dữ liệu lưu. */
public record AccountDetail(String code, String phoneNumber, String termsVersion, Instant consentedAt) {
    /** Chiếu aggregate đã đọc thành view, giữ nguyên cặp consent đầu tiên hoặc cả hai null. */
    public static AccountDetail from(Account account) {
        TermsConsent consent = account.consent().orElse(null);
        return new AccountDetail(account.code(), account.phoneNumber().value(),
                consent == null ? null : consent.version(), consent == null ? null : consent.consentedAt());
    }

    /** Chỉ in mã nghiệp vụ, không đưa số điện thoại vào log. */
    @Override
    public String toString() { return "AccountDetail[code=" + code + "]"; }
}
