package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.DocumentUpload;
import java.util.Optional;

/** Cổng đọc lượt tải BR-130, luôn giới hạn bởi chủ để không lộ object key chéo khách. */
public interface ReadDocumentUploadPort {
    /**
     * Chủ phải lấy từ người gọi đã xác thực tại use case, không lấy từ request.
     * Không tìm thấy và sai chủ đều trả rỗng; không sinh URL, không kiểm thay cho UNIQUE khi nộp.
     */
    Optional<DocumentUpload> findByCodeAndOwner(String code, String accountCode);
}
