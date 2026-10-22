package com.carrental.keycloak.otp;

/** Biên gửi SMS theo ADR-0006; adapter local được đóng gói riêng khỏi JAR lõi. */
public interface SmsSender {
    /** Gửi mã; không trả nội dung SMS ra response đăng nhập. */
    void send(String phoneNumber, String challengeId, String code);
}
