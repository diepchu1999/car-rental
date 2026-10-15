package com.carrental.keycloak.otp;

import com.carrental.keycloak.support.TestSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm biên kỹ thuật trước khi chạm database; không dùng phone/IP thô làm khoá hay thông báo lỗi. */
class OtpScopeTest {
    /** Số được chuẩn hoá tại biên và không rò qua toString của scope. */
    @Test void normalizesPhoneAndRedactsScope() {
        var scope = new OtpScope("realm", "private-session", "0912345678", OtpScope.Purpose.CUSTOMER_LOGIN);
        assertEquals("+84912345678", scope.phoneNumber());
        assertFalse(scope.toString().contains("912345678"));
        assertFalse(scope.toString().contains("private-session"));
    }

    /** Không cho challenge thiếu phạm vi phiên/realm/mục đích. */
    @Test void rejectsMissingScope() {
        assertThrows(IllegalArgumentException.class, () -> new OtpScope("", "session", "0912345678", OtpScope.Purpose.CUSTOMER_LOGIN));
        assertThrows(IllegalArgumentException.class, () -> new OtpScope("realm", "", "0912345678", OtpScope.Purpose.CUSTOMER_LOGIN));
        assertThrows(IllegalArgumentException.class, () -> new OtpScope("realm", "session", "0912345678", null));
    }

    /** IP phải là địa chỉ peer dạng số; không chấp nhận hostname, header chuyển tiếp hoặc ký tự log. */
    @Test void rejectsUntrustedPeerAddressBeforeDatabaseAccess() {
        var store = new OtpStore(null, OtpPolicy.from(TestSettings.otp()),
                new OtpSecrets(TestSettings.otp().get("CAR_RENTAL_OTP_HMAC_SECRET")));
        var scope = new OtpScope("realm", "session", "0912345678", OtpScope.Purpose.CUSTOMER_LOGIN);
        for (String ip : new String[] {null, "", "localhost", "abcdef", "127.1", "1.2.3.4, 5.6.7.8", "1.2.3.4\nforged", "999.999.999.999"})
            assertThrows(IllegalArgumentException.class, () -> store.verify(scope, ip, "challenge", "123456"));
    }
}
