package com.carrental.identity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Security-guideline §3: khoá bắt buộc, độc lập, không phản chiếu giá trị trong lỗi startup. */
class DocumentCryptoConfigurationTest {
    private static final String PREFIX = "car-rental.identity.documents.crypto.";
    private static final String ENCRYPTION = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String HASHING = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    private final DocumentCryptoConfiguration configuration = new DocumentCryptoConfiguration();

    /** Cấu hình hợp lệ tạo được bean thật, không cần CSDL, MinIO hay khoá từ .env. */
    @Test
    void startsWithTwoIndependentKeys() {
        new ApplicationContextRunner().withUserConfiguration(DocumentCryptoConfiguration.class)
                .withPropertyValues(PREFIX + "key-version=1", PREFIX + "encryption-key=" + ENCRYPTION,
                        PREFIX + "hash-key=" + HASHING)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var keys = context.getBean(DocumentCryptoKeys.class);
                    assertEquals(1, keys.version());
                    assertEquals("AES", keys.encryptionKey().getAlgorithm());
                    assertEquals("HmacSHA256", keys.hashKey().getAlgorithm());
                    assertArrayEquals(Base64.getDecoder().decode(ENCRYPTION), keys.encryptionKey().getEncoded());
                    assertArrayEquals(Base64.getDecoder().decode(HASHING), keys.hashKey().getEncoded());
                    assertEquals("DocumentCryptoKeys[redacted]", keys.toString());
                });
    }

    /** Thiếu từng biến đều làm context khởi động thất bại thay vì dùng khoá mặc định. */
    @ParameterizedTest
    @ValueSource(strings = {"key-version", "encryption-key", "hash-key"})
    void failsStartupWhenAPropertyIsMissing(String missing) {
        var properties = new MockEnvironment();
        if (!missing.equals("key-version")) properties.withProperty(PREFIX + "key-version", "1");
        if (!missing.equals("encryption-key")) properties.withProperty(PREFIX + "encryption-key", ENCRYPTION);
        if (!missing.equals("hash-key")) properties.withProperty(PREFIX + "hash-key", HASHING);
        new ApplicationContextRunner().withUserConfiguration(DocumentCryptoConfiguration.class)
                .withInitializer(context -> context.setEnvironment(properties))
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String trace = stackTrace(context.getStartupFailure());
                    assertTrue(trace.contains("Invalid car-rental.identity.documents.crypto configuration"));
                    assertFalse(trace.contains(ENCRYPTION));
                    assertFalse(trace.contains(HASHING));
                });
    }

    /** Phiên bản sai không được echo nguyên văn, kể cả đó là một chuỗi nhạy cảm nhập nhầm. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "", "1.5", "9999999999999999999999", "secret-version-sentinel"})
    void rejectsInvalidKeyVersion(String version) {
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "key-version", version));
    }

    /** Base64 hỏng, placeholder và khoá rỗng bị chặn trước khi phục vụ request. */
    @ParameterizedTest
    @ValueSource(strings = {"", "secret-key-sentinel", "${MISSING_DOCUMENT_KEY}", "AAAA\n"})
    void rejectsMalformedKeys(String key) {
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "encryption-key", key));
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "hash-key", key));
    }

    /** Không nhận AES-128/192 hoặc các độ dài khác thay cho cặp khoá 256 bit đã chọn. */
    @ParameterizedTest
    @ValueSource(ints = {1, 16, 24, 31, 33, 64})
    void rejectsWrongKeySizes(int length) {
        String key = Base64.getEncoder().encodeToString(new byte[length]);
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "encryption-key", key));
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "hash-key", key));
    }

    /** Dùng lại cùng khoá cho mã hoá và HMAC là cấu hình không hợp lệ. */
    @Test
    void rejectsReusedKeyMaterial() {
        assertSafeConfigurationFailure(environment().withProperty(PREFIX + "hash-key", ENCRYPTION));
    }

    /** Bản sao key material không bị sửa bởi người gọi giữ mảng đầu vào hoặc kết quả getEncoded. */
    @Test
    void defensivelyCopiesKeyMaterial() {
        byte[] encryption = Base64.getDecoder().decode(ENCRYPTION);
        byte[] hashing = Base64.getDecoder().decode(HASHING);
        var keys = new DocumentCryptoKeys(1, encryption, hashing);
        Arrays.fill(encryption, (byte) 0);
        Arrays.fill(hashing, (byte) 0);
        Arrays.fill(keys.encryptionKey().getEncoded(), (byte) 0);
        assertArrayEquals(Base64.getDecoder().decode(ENCRYPTION), keys.encryptionKey().getEncoded());
        assertArrayEquals(Base64.getDecoder().decode(HASHING), keys.hashKey().getEncoded());
    }

    /** Constructor không để đường đi ngoài Spring bỏ qua quy tắc cỡ khoá và phiên bản. */
    @Test
    void rejectsInvalidKeysEvenWithoutSpring() {
        byte[] encryption = Base64.getDecoder().decode(ENCRYPTION);
        byte[] hashing = Base64.getDecoder().decode(HASHING);
        assertThrows(IllegalArgumentException.class, () -> new DocumentCryptoKeys(0, encryption, hashing));
        assertThrows(IllegalArgumentException.class, () -> new DocumentCryptoKeys(1, null, hashing));
        assertThrows(IllegalArgumentException.class, () -> new DocumentCryptoKeys(1, encryption, null));
        assertThrows(IllegalArgumentException.class, () -> new DocumentCryptoKeys(1, encryption, encryption));
    }

    /** Đảm bảo exception không giữ cause/suppressed hoặc key material, kể cả khi in cả stack trace. */
    private void assertSafeConfigurationFailure(MockEnvironment environment) {
        var failure = assertThrows(IllegalStateException.class, () -> configuration.documentCryptoKeys(environment));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        String trace = stackTrace(failure);
        assertTrue(trace.contains(PREFIX.substring(0, PREFIX.length() - 1)));
        assertFalse(trace.contains(ENCRYPTION));
        assertFalse(trace.contains(HASHING));
        assertFalse(trace.contains("secret-key-sentinel"));
        assertFalse(trace.contains("secret-version-sentinel"));
        assertFalse(trace.contains("MISSING_DOCUMENT_KEY"));
    }

    /** Môi trường chỉ gồm giá trị test đã công khai, không đọc shell hay .env cá nhân. */
    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty(PREFIX + "key-version", "1")
                .withProperty(PREFIX + "encryption-key", ENCRYPTION).withProperty(PREFIX + "hash-key", HASHING);
    }

    /** Thu chuỗi stack trace thật để kiểm cả cause và suppressed, không chỉ message bề mặt. */
    private static String stackTrace(Throwable failure) {
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
