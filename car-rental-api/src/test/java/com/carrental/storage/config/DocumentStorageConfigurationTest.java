package com.carrental.storage.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: fail-fast cấu hình kho riêng, không khởi tạo mạng và không phản chiếu secret. */
class DocumentStorageConfigurationTest {
    private final DocumentStorageConfiguration configuration = new DocumentStorageConfiguration();

    /** Cấu hình hợp lệ tạo SDK mà không cần MinIO đang chạy; client được đóng sau test. */
    @Test void createsDedicatedClientWithoutNetworkAccess() throws Exception {
        try (var client = configuration.documentMinioClient(environment())) {
            assertNotNull(client);
            assertEquals("documents-test", configuration.documentBucket(environment()));
        }
    }

    /** Không nhận URL có user-info, query, fragment hoặc đường dẫn thay cho endpoint. */
    @ParameterizedTest
    @ValueSource(strings = {"https://secret-sentinel@storage.invalid:9000", "http://storage.invalid/?secret-sentinel",
            "http://storage.invalid/#secret-sentinel", "http://storage.invalid/secret-sentinel", "file:///secret-sentinel"})
    void rejectsUnsafeEndpointWithoutEchoingIt(String endpoint) {
        var failure = assertThrows(IllegalStateException.class, () -> configuration.documentMinioClient(
                environment().withProperty("car-rental.storage.endpoint", endpoint)));
        assertFalse(failure.toString().contains("secret-sentinel"));
        assertNull(failure.getCause());
    }

    /** Không vô tình lấy account root đã export cùng môi trường cho SDK ứng dụng. */
    @Test void rejectsRootCredentials() {
        var environment = environment().withProperty("MINIO_ROOT_USER", "documents-test-app");
        assertThrows(IllegalStateException.class, () -> configuration.documentMinioClient(environment));
    }

    /** Biến bắt buộc rỗng làm startup lỗi, không âm thầm dùng account mặc định. */
    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "access-key", "secret-key"})
    void rejectsMissingClientConfiguration(String property) {
        assertThrows(IllegalStateException.class, () -> configuration.documentMinioClient(
                environment().withProperty("car-rental.storage." + property, "")));
    }

    /** Tên bucket không chấp nhận path, ký tự điều khiển hoặc placeholder chưa được resolve. */
    @ParameterizedTest
    @ValueSource(strings = {"", "a", "-documents", "documents-", "documents/private", "documents\nforged"})
    void rejectsInvalidBucket(String bucket) {
        assertThrows(IllegalStateException.class, () -> configuration.documentBucket(
                environment().withProperty("car-rental.storage.bucket", bucket)));
    }

    /** Cấu hình thuần giả, không đọc .env cá nhân. */
    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("car-rental.storage.endpoint", "http://storage.invalid:9000")
                .withProperty("car-rental.storage.bucket", "documents-test")
                .withProperty("car-rental.storage.access-key", "documents-test-app")
                .withProperty("car-rental.storage.secret-key", "test-only-storage-secret");
    }
}
