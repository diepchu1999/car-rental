package com.carrental.keycloak.smslocal;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-0006: adapter log OTP chỉ có thể được tạo/sử dụng trong runtime dev. */
class LocalLogSmsSenderTest {
    /** Không có cờ SMS riêng cho phép lách kiểm tra chế độ server. */
    @Test void rejectsNormalServerAndRechecksBeforeSend() {
        String previous = System.getProperty("kc.profile");
        try {
            System.setProperty("kc.profile", "prod");
            assertThrows(IllegalStateException.class, LocalLogSmsSender::new);
            System.setProperty("kc.profile", "dev");
            var sender = new LocalLogSmsSender();
            System.setProperty("kc.profile", "prod");
            assertThrows(IllegalStateException.class, () -> sender.send("+84912345678", UUID.randomUUID().toString(), "012345"));
        } finally { restore(previous); }
    }

    /** Dữ liệu chèn dòng log bị từ chối ngay cả trong dev. */
    @Test void rejectsControlCharactersInLoggedFields() {
        String previous = System.getProperty("kc.profile");
        try {
            System.setProperty("kc.profile", "dev");
            var sender = new LocalLogSmsSender();
            assertThrows(IllegalArgumentException.class, () -> sender.send("+84912345678", "fake\nline", "012345"));
            assertThrows(IllegalArgumentException.class, () -> sender.send("+84912345678", UUID.randomUUID().toString(), "123\n45"));
        } finally { restore(previous); }
    }

    /** Không để global property ảnh hưởng test khác. */
    private void restore(String previous) {
        if (previous == null) System.clearProperty("kc.profile"); else System.setProperty("kc.profile", previous);
    }
}
