package com.carrental.storage;

import com.carrental.storage.api.DocumentStorageDirectory;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: MinIO thật, account ứng dụng thật và HTTP signed URL thật; không chạm MinIO local. */
@Testcontainers
@SpringJUnitConfig(MinioDocumentStorageIntegrationTest.StorageContext.class)
@TestPropertySource(properties = {
        "car-rental.storage.bucket=documents-test",
        "car-rental.storage.access-key=documents-test-app",
        "car-rental.storage.secret-key=test-only-storage-secret",
        "car-rental.storage.documents.max-bytes=5000000",
        "car-rental.storage.documents.max-pixels=40000000",
        "car-rental.storage.documents.upload-lifetime=PT5M",
        "car-rental.storage.documents.view-lifetime=PT60S"
})
class MinioDocumentStorageIntegrationTest {
    private static final Path ROOT = repositoryRoot();
    private static final String FOREIGN_BUCKET = "unrelated-test";
    private static final String FOREIGN_OBJECT = "sealed/fixture.txt";
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(image()))
            .withEnv("MINIO_ROOT_USER", "test-root")
            .withEnv("MINIO_ROOT_PASSWORD", "test-only-root-secret")
            .withEnv("CAR_RENTAL_STORAGE_BUCKET", "documents-test")
            .withEnv("CAR_RENTAL_STORAGE_ACCESS_KEY", "documents-test-app")
            .withEnv("CAR_RENTAL_STORAGE_SECRET_KEY", "test-only-storage-secret")
            .withEnv("CAR_RENTAL_STORAGE_BOOTSTRAP_ENDPOINT", "http://127.0.0.1:9000")
            .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infra/minio/bootstrap-documents.sh")), "/tmp/bootstrap.sh")
            .withExposedPorts(9000).withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired DocumentStorageDirectory storage;
    @Autowired MinioClient applicationClient;

    /** Bootstrap hai lần; root chỉ tạo thêm dữ liệu ngoài phạm vi trong container test, không dùng .env. */
    @BeforeAll static void bootstrap() throws Exception {
        assertEquals(0, MINIO.execInContainer("sh", "/tmp/bootstrap.sh").getExitCode(), "Storage bootstrap failed.");
        assertEquals(0, MINIO.execInContainer("sh", "/tmp/bootstrap.sh").getExitCode(), "Repeated bootstrap failed.");
        try (var root = MinioClient.builder().endpoint(endpoint()).region("us-east-1")
                .credentials("test-root", "test-only-root-secret").build()) {
            root.makeBucket(MakeBucketArgs.builder().bucket(FOREIGN_BUCKET).build());
            byte[] content = "outside-application-scope".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            root.putObject(PutObjectArgs.builder().bucket(FOREIGN_BUCKET).object(FOREIGN_OBJECT)
                    .stream(new ByteArrayInputStream(content), content.length, -1).contentType("text/plain").build());
        }
    }

    /** Chỉ endpoint container động, không dùng .env hoặc port cố định của Tech Owner. */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("car-rental.storage.endpoint", MinioDocumentStorageIntegrationTest::endpoint);
    }

    /** PNG gửi MIME giả vẫn được xác định bằng bytes; ảnh riêng tư và URL GET không cache. */
    @Test void uploadsSealsAndViewsPrivateImageWithApplicationCredentials() throws Exception {
        byte[] bytes = DocumentImageFixtures.image("png");
        var upload = storage.createUpload();
        assertTrue(URI.create(upload.url()).getRawQuery().contains("X-Amz-Expires=300"));
        assertEquals(200, put(upload.url(), bytes));
        var sealed = storage.sealUpload(upload.objectKey());
        assertEquals("image/png", sealed.contentType());
        assertEquals(bytes.length, sealed.sizeBytes());
        var view = storage.createView(sealed.objectKey());
        assertTrue(URI.create(view.url()).getRawQuery().contains("X-Amz-Expires=60"));
        var response = get(view.url());
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes, response.body());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(403, get(endpoint() + "/documents-test/" + sealed.objectKey()).statusCode());
    }

    /** PUT cũ sửa được vùng tạm nhưng tuyệt đối không sửa ảnh đã chốt. */
    @Test void reusingUploadUrlCannotReplaceSealedEvidence() throws Exception {
        byte[] original = DocumentImageFixtures.image("png");
        var upload = storage.createUpload();
        assertEquals(200, put(upload.url(), original));
        var sealed = storage.sealUpload(upload.objectKey());
        assertEquals(200, put(upload.url(), "replaced-upload".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertArrayEquals(original, get(storage.createView(sealed.objectKey()).url()).body());
        assertEquals(ErrorCode.DOCUMENT_IMAGE_INVALID, assertThrows(DomainException.class,
                () -> storage.sealUpload(upload.objectKey())).errorCode());
    }

    /** File 6 MB, văn bản giả ảnh và object chưa tải bị từ chối đúng mã. */
    @Test void rejectsOversizedInvalidAndMissingUploads() throws Exception {
        var missing = storage.createUpload();
        assertEquals(ErrorCode.DOCUMENT_UPLOAD_NOT_READY, assertThrows(DomainException.class,
                () -> storage.sealUpload(missing.objectKey())).errorCode());
        assertEquals(200, put(missing.url(), new byte[6000000]));
        assertEquals(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE, assertThrows(DomainException.class,
                () -> storage.sealUpload(missing.objectKey())).errorCode());
        assertEquals(200, put(missing.url(), "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(ErrorCode.DOCUMENT_IMAGE_INVALID, assertThrows(DomainException.class,
                () -> storage.sealUpload(missing.objectKey())).errorCode());
    }

    /** HEIC đi qua cùng đường S3 thật, metadata không lấy từ MIME gửi lên. */
    @Test void sealsHeicThroughRealStorage() throws Exception {
        byte[] bytes = DocumentImageFixtures.heic();
        var upload = storage.createUpload();
        assertEquals(200, put(upload.url(), bytes));
        var sealed = storage.sealUpload(upload.objectKey());
        assertEquals("image/heic", sealed.contentType());
        assertArrayEquals(bytes, get(storage.createView(sealed.objectKey()).url()).body());
    }

    /** Danh sách chỉ có bucket được phép; account ứng dụng không đọc bucket khác, tạo bucket hoặc xoá ảnh. */
    @Test void applicationAccountCannotAdministerOrDeleteObjects() throws Exception {
        var upload = storage.createUpload();
        assertEquals(200, put(upload.url(), DocumentImageFixtures.image("png")));
        var sealed = storage.sealUpload(upload.objectKey());
        var deletion = assertThrows(ErrorResponseException.class, () -> applicationClient.removeObject(
                RemoveObjectArgs.builder().bucket("documents-test").object(sealed.objectKey()).build()));
        assertEquals("AccessDenied", deletion.errorResponse().code());
        // MinIO lọc danh sách theo quyền ListBucket/GetBucketLocation, không nhất thiết trả 403.
        assertEquals(List.of("documents-test"), applicationClient.listBuckets().stream()
                .map(bucket -> bucket.name()).toList());
        var reading = assertThrows(ErrorResponseException.class, () -> {
            try (var object = applicationClient.getObject(GetObjectArgs.builder()
                    .bucket(FOREIGN_BUCKET).object(FOREIGN_OBJECT).build())) {
                object.read();
            }
        });
        assertEquals("AccessDenied", reading.errorResponse().code());
        var creation = assertThrows(ErrorResponseException.class, () -> applicationClient.makeBucket(
                MakeBucketArgs.builder().bucket("application-must-not-create").build()));
        assertEquals("AccessDenied", creation.errorResponse().code());
        assertEquals(200, get(storage.createView(sealed.objectKey()).url()).statusCode());
    }

    /** Thay thời hạn trên URL không kéo dài được quyền vì chữ ký không còn hợp lệ. */
    @Test void rejectsTamperedSignedUrl() throws Exception {
        var upload = storage.createUpload();
        assertEquals(200, put(upload.url(), DocumentImageFixtures.image("png")));
        var sealed = storage.sealUpload(upload.objectKey());
        String view = storage.createView(sealed.objectKey()).url();
        assertEquals(403, get(view.replace("X-Amz-Expires=60", "X-Amz-Expires=3600")).statusCode());
    }

    /** PUT binary với MIME cố ý sai; không log URL hoặc nội dung response. */
    private int put(String url, byte[] content) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** GET thật, không giả nội dung từ SDK/mock. */
    private HttpResponse<byte[]> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Endpoint chỉ phục vụ container test. */
    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }

    /** Dùng đúng image đã commit như local, thiếu biến phải lỗi thay vì dùng latest. */
    private static String image() {
        try {
            return Files.readAllLines(ROOT.resolve(".env.example")).stream()
                    .filter(line -> line.startsWith("MINIO_IMAGE=")).map(line -> line.substring("MINIO_IMAGE=".length()).strip())
                    .filter(value -> !value.isBlank()).findFirst().orElseThrow();
        } catch (Exception failure) { throw new IllegalStateException("MINIO_IMAGE is required in .env.example.", failure); }
    }

    /** Đi ngược cây để chạy được ở IDE hoặc Maven module. */
    private static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent())
            if (Files.isRegularFile(path.resolve(".env.example"))) return path;
        throw new IllegalStateException("Cannot locate repository .env.example.");
    }

    /** Chỉ lắp module storage và clock; không kéo PostgreSQL hay giả JWT để thử storage. */
    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan("com.carrental.storage")
    static class StorageContext {
        /** Clock phục vụ thời hạn liên kết, không đồng hồ giả khác với MinIO. */
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
