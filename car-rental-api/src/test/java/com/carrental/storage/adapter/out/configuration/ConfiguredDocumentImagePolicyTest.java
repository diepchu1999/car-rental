package com.carrental.storage.adapter.out.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: cấu hình sai làm startup thất bại, không fallback im lặng. */
class ConfiguredDocumentImagePolicyTest {
    /** Giá trị không bị đóng cứng vào parser hoặc service. */
    @Test void readsAllFourValues() {
        var policy = new ConfiguredDocumentImagePolicy(environment()).at(Instant.EPOCH);
        assertEquals(5000000, policy.maxBytes());
        assertEquals(40000000, policy.maxPixels());
        assertEquals(300, policy.uploadLifetime().getSeconds());
        assertEquals(60, policy.viewLifetime().getSeconds());
    }

    /** Số âm/0, quá miền đọc, hạn lẻ hoặc quá giới hạn S3 đều bị từ chối. */
    @ParameterizedTest
    @CsvSource({"max-bytes,0", "max-bytes,2147483647", "max-pixels,-1",
            "upload-lifetime,PT0S", "view-lifetime,PT0.5S", "view-lifetime,P8D"})
    void invalidConfigurationFailsFast(String name, String value) {
        var env = environment().withProperty("car-rental.storage.documents." + name, value);
        assertThrows(IllegalStateException.class, () -> new ConfiguredDocumentImagePolicy(env));
    }

    /** Thiếu cấu hình không thành policy rỗng. */
    @Test void missingConfigurationFailsFast() {
        assertThrows(IllegalStateException.class, () -> new ConfiguredDocumentImagePolicy(new MockEnvironment()));
    }

    /** Bộ tham số được duyệt, chỉ phục vụ test. */
    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("car-rental.storage.documents.max-bytes", "5000000")
                .withProperty("car-rental.storage.documents.max-pixels", "40000000")
                .withProperty("car-rental.storage.documents.upload-lifetime", "PT5M")
                .withProperty("car-rental.storage.documents.view-lifetime", "PT60S");
    }
}
