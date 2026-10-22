package com.carrental.keycloak.otp;

/** Kết quả nội bộ của SPI, không phải mã lỗi API backend và không chứa mã OTP. */
public enum OtpResult {
    SENT, VERIFIED, INVALID, EXPIRED, CONSUMED, ATTEMPTS_EXHAUSTED,
    RESEND_TOO_SOON, PHONE_RATE_LIMITED, IP_RATE_LIMITED
}
