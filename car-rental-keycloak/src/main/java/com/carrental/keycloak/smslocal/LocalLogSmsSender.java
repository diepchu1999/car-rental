package com.carrental.keycloak.smslocal;

import com.carrental.keycloak.otp.SmsSender;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Environment;

/** SMS giả ADR-0006, chỉ đóng trong JAR -local-sms; không dùng với số điện thoại thật. */
public final class LocalLogSmsSender implements SmsSender {
    private static final Logger LOG = Logger.getLogger(LocalLogSmsSender.class);
    /** Chặn ngay khi nạp adapter ngoài runtime dev của Keycloak. */
    public LocalLogSmsSender() { requireDev(); }
    /** Chỉ in mã và mã challenge, không in số điện thoại, mật khẩu, token hoặc body. */
    @Override public void send(String phoneNumber, String challengeId, String code) {
        requireDev();
        if (challengeId == null || !challengeId.matches("[a-f0-9-]{36}")
                || code == null || !code.matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException("Invalid local SMS log fields.");
        }
        LOG.infof("LOCAL TEST SMS ONLY challenge=%s otp=%s", challengeId, code);
    }
    /** Không có cờ riêng cho phép bật adapter này trong chế độ server thường. */
    private static void requireDev() {
        if (!Environment.isDevMode()) throw new IllegalStateException("Local SMS requires Keycloak start-dev.");
    }
}
