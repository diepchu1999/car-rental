package com.carrental.keycloak.otp;

import com.carrental.keycloak.support.TestSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu ra sinh mã và cách phân miền HMAC; không kiểm ngẫu nhiên bằng xác suất dễ chập chờn. */
class OtpSecretsTest {
    /** Mã luôn đủ chữ số, kể cả khi ngẫu nhiên sinh số 0 ở đầu. */
    @Test void generatesExactConfiguredLength() {
        var secrets = secrets();
        for (int i = 0; i < 100; i++) assertTrue(secrets.generate(6).matches("[0-9]{6}"));
        assertThrows(IllegalArgumentException.class, () -> secrets.generate(0));
        assertThrows(IllegalArgumentException.class, () -> secrets.generate(10));
    }

    /** Khoá rỗng, sai định dạng hay quá ngắn bị từ chối. */
    @Test void rejectsMissingOrWeakSecret() {
        for (String value : new String[] {null, "", "invalid!", "YQ=="})
            assertThrows(IllegalArgumentException.class, () -> new OtpSecrets(value));
    }

    /** Phân miền và ghép theo độ dài chặn nhập nhằng giữa các bộ đầu vào. */
    @Test void separatesDomainsAndComponents() {
        var secrets = secrets();
        var digest = secrets.digest("code", "challenge", "012345");
        assertTrue(digest.matches("[a-f0-9]{64}"));
        assertTrue(secrets.matches(digest, secrets.digest("code", "challenge", "012345")));
        assertFalse(secrets.matches(digest, secrets.digest("phone", "challenge", "012345")));
        assertNotEquals(secrets.digest("ab", "c"), secrets.digest("a", "bc"));
    }

    /** Khoá công khai chỉ dành cho container và unit test. */
    private OtpSecrets secrets() { return new OtpSecrets(TestSettings.otp().get("CAR_RENTAL_OTP_HMAC_SECRET")); }
}
