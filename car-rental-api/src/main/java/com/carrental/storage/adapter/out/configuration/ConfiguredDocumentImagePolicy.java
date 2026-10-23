package com.carrental.storage.adapter.out.configuration;

import com.carrental.storage.application.port.out.DocumentImagePolicyPort;
import com.carrental.storage.domain.DocumentImagePolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * BR-130, ADR-0013: nguồn local tạm, chưa có lịch sử hiệu lực hay admin chỉnh.
 * Đổi cấu hình cần restart; at chưa được dùng chọn phiên bản.
 */
@Component
class ConfiguredDocumentImagePolicy implements DocumentImagePolicyPort {
    private final DocumentImagePolicy policy;
    /** Đọc và kiểm cấu hình ngay khi startup; lỗi không lặp lại giá trị đầu vào. */
    ConfiguredDocumentImagePolicy(Environment environment) {
        try {
            String prefix = "car-rental.storage.documents.";
            policy = new DocumentImagePolicy(Long.parseLong(environment.getRequiredProperty(prefix + "max-bytes")),
                    Long.parseLong(environment.getRequiredProperty(prefix + "max-pixels")),
                    Duration.parse(environment.getRequiredProperty(prefix + "upload-lifetime")),
                    Duration.parse(environment.getRequiredProperty(prefix + "view-lifetime")));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid car-rental.storage.documents configuration.");
        }
    }
    /** Trả policy bất biến, không tự lấy thời gian hiện tại để thay mốc caller đã chọn. */
    @Override public DocumentImagePolicy at(Instant at) { Objects.requireNonNull(at, "at"); return policy; }
}
