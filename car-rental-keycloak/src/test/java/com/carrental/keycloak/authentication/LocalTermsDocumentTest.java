package com.carrental.keycloak.authentication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** BR-120: văn bản thử phải nhận diện rõ, có phiên bản, không được lặng lẽ dùng như điều khoản thật. */
class LocalTermsDocumentTest {
    /** Nội dung được đóng trong resource và nhãn thử là một phần của văn bản hiển thị. */
    @Test void loadsClearlyMarkedLocalDocument() {
        var terms = LocalTermsDocument.load("local-test-v1");
        assertTrue(terms.text().startsWith("ĐIỀU KHOẢN THỬ — CHỈ DÙNG LOCAL"));
        assertEquals("local-test-v1", terms.version());
        assertEquals(64, terms.fingerprint().length());
    }

    /** Thiếu hoặc sai phiên bản phải lỗi thay vì dùng mặc định. */
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "fake\nversion", "<script>"})
    void rejectsUnsafeOrMissingVersion(String version) {
        assertThrows(IllegalArgumentException.class, () -> LocalTermsDocument.load(version));
    }

    /** Đổi nội dung hoặc phiên bản đều đổi dấu tài liệu đã hiển thị. */
    @Test void fingerprintIncludesBothVersionAndText() {
        var original = LocalTermsDocument.load("local-test-v1");
        assertNotEquals(original.fingerprint(), LocalTermsDocument.load("local-test-v2").fingerprint());
        assertNotEquals(original.fingerprint(), new LocalTermsDocument(original.version(), original.text() + "\nChanged").fingerprint());
        assertThrows(IllegalArgumentException.class, () -> new LocalTermsDocument("v1", "Unmarked text"));
    }

    /** Chế độ thường không thể dùng văn bản local để lấy sự đồng ý. */
    @Test void rejectsNormalRuntime() {
        String previous = System.getProperty("kc.profile");
        try {
            System.setProperty("kc.profile", "prod");
            assertThrows(IllegalStateException.class, () -> LocalTermsDocument.load("local-test-v1").requireLocalRuntime());
            System.setProperty("kc.profile", "dev");
            assertDoesNotThrow(() -> LocalTermsDocument.load("local-test-v1").requireLocalRuntime());
        } finally {
            if (previous == null) System.clearProperty("kc.profile"); else System.setProperty("kc.profile", previous);
        }
    }
}
