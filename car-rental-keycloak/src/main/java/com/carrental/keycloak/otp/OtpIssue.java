package com.carrental.keycloak.otp;

import java.time.Instant;

/** Biên nhận phát mã; mã xác minh chỉ đi vào SmsSender, không trở về authenticator/client. */
public record OtpIssue(OtpResult result, String challengeId, Instant expiresAt) {
    /** Tạo kết quả từ chối không mang định danh hay thời hạn giả. */
    public static OtpIssue rejected(OtpResult result) { return new OtpIssue(result, null, null); }
}
