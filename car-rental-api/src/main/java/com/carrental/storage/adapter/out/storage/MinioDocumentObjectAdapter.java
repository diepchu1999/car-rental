package com.carrental.storage.adapter.out.storage;

import com.carrental.storage.application.port.out.DocumentObjectPort;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Map;

/** S3 riêng tư BR-130; exception SDK không được làm lộ object key, URL, header hoặc credential. */
@Component
class MinioDocumentObjectAdapter implements DocumentObjectPort {
    private final MinioClient client;
    private final String bucket;

    /** Chỉ nhận SDK dùng tài khoản ứng dụng và bucket cố định. */
    MinioDocumentObjectAdapter(MinioClient client, @Qualifier("documentBucket") String bucket) {
        this.client = client; this.bucket = bucket;
    }

    /** Ký đúng một key tạm; không cho caller truyền HTTP method hoặc query tùy ý. */
    @Override public String uploadUrl(String key, Duration lifetime) { return sign(key, lifetime, Method.PUT); }

    /** Kiểm HEAD để từ chối sớm, rồi giới hạn GET độc lập để chống thay object sau HEAD. */
    @Override public byte[] read(String key, long maxBytes) {
        try {
            var stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            if (stat.size() > maxBytes) throw DomainException.ruleViolation(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE);
            try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                byte[] bytes = input.readNBytes(Math.toIntExact(maxBytes + 1));
                if (bytes.length > maxBytes) throw DomainException.ruleViolation(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE);
                return bytes;
            }
        } catch (DomainException known) { throw known;
        } catch (ErrorResponseException failure) {
            if ("NoSuchKey".equals(failure.errorResponse().code())) {
                throw DomainException.ruleViolation(ErrorCode.DOCUMENT_UPLOAD_NOT_READY);
            }
            throw safeFailure(failure);
        } catch (Exception failure) { throw safeFailure(failure); }
    }

    /** Ghi bytes đã kiểm, không sao chép lại vùng upload và không dùng MIME client khai. */
    @Override public void put(String key, byte[] bytes, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(contentType)
                    .headers(Map.of("Cache-Control", "no-store")).build());
        } catch (Exception failure) { throw safeFailure(failure); }
    }

    /** Liên kết GET mang chỉ dẫn không cache; quyền truy cập/audit thuộc use case identity. */
    @Override public String viewUrl(String key, Duration lifetime) { return sign(key, lifetime, Method.GET); }

    /** Không log SDK request/response và không bật traceOn của MinIO. */
    private String sign(String key, Duration lifetime, Method method) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder().method(method).bucket(bucket).object(key)
                    .expiry(Math.toIntExact(lifetime.getSeconds()));
            if (method == Method.GET) builder.extraQueryParams(Map.of("response-cache-control", "no-store"));
            return client.getPresignedObjectUrl(builder.build());
        } catch (Exception failure) { throw safeFailure(failure); }
    }

    /**
     * Giữ loại lỗi và vị trí gốc để chẩn đoán, bỏ message/cause/suppressed có thể chứa bí mật.
     * Không sửa exception SDK dùng chung, không trả nội dung này ra JSON (INTERNAL_ERROR ở biên).
     */
    private static IllegalStateException safeFailure(Exception failure) {
        var safe = new IllegalStateException("Document storage operation failed (" + failure.getClass().getSimpleName() + ").");
        safe.setStackTrace(failure.getStackTrace());
        return safe;
    }
}
