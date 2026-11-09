package com.carrental.storage.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import java.net.URI;

/** Lắp SDK bằng tài khoản ứng dụng; không gọi mạng, tạo bucket hay cấp quyền trong startup backend. */
@Configuration(proxyBeanMethods = false)
class DocumentStorageConfiguration {
    /** Kiểm cấu hình và tạo SDK; không giữ exception gốc có thể chứa URL/secret cấu hình. */
    @Bean(destroyMethod = "close")
    MinioClient documentMinioClient(Environment environment) {
        try {
            var endpoint = URI.create(required(environment, "endpoint"));
            if (!java.util.Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || (endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            String access = required(environment, "access-key");
            if (access.equals(environment.getProperty("MINIO_ROOT_USER"))) throw new IllegalArgumentException();
            return MinioClient.builder().endpoint(endpoint.toString()).region("us-east-1")
                    .credentials(access, required(environment, "secret-key")).build();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid car-rental.storage configuration; use dedicated application credentials.");
        }
    }

    /** Trả tên bucket đã kiểm, không nhận bucket từ request. */
    @Bean("documentBucket")
    String documentBucket(Environment environment) {
        String bucket = required(environment, "bucket");
        if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalStateException("Invalid car-rental.storage.bucket.");
        }
        return bucket;
    }

    /** Chỉ đưa tên property vào lỗi, không đưa giá trị nhạy cảm. */
    private static String required(Environment environment, String name) {
        try {
            String value = environment.getRequiredProperty("car-rental.storage." + name);
            if (!value.isBlank()) return value;
        } catch (RuntimeException ignored) { }
        throw new IllegalStateException("Missing car-rental.storage." + name + ".");
    }
}
