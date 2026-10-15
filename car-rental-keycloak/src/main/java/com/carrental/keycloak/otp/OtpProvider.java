package com.carrental.keycloak.otp;

import org.keycloak.provider.Provider;

/** SPI mã dùng một lần; authenticator chỉ nhận kết quả, không tự giữ bộ đếm trong session/JVM. */
public interface OtpProvider extends Provider {
    /** Cấp mã sau khi kiểm mọi hạn mức; địa chỉ phải lấy từ kết nối đã được tin cậy. */
    OtpIssue issue(OtpScope scope, String remoteAddress);

    /** Tiêu thụ mã hoặc ghi lần sai trong transaction độc lập, kể cả đăng nhập sau đó rollback. */
    OtpResult verify(OtpScope scope, String remoteAddress, String challengeId, String code);
}
