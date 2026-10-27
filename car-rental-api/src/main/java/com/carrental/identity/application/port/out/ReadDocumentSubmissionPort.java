package com.carrental.identity.application.port.out;

import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.application.view.DocumentHeadSummary;

import java.util.List;
import java.util.Optional;

/** Cổng đọc theo mã GT và chủ BR-130; không có đường đọc giấy tờ tuỳ ý chỉ bằng mã. */
public interface ReadDocumentSubmissionPort {
    /** Chủ lấy từ caller; mã không tồn tại hoặc khác chủ đều trả rỗng, không giải mã số hay ký URL. */
    Optional<DocumentSubmissionDetail> findByCodeAndOwner(String code, String accountCode);

    /** Đọc cả kết quả xét duyệt của một phiên bản đúng chủ, kể cả phiên bản đã bị thay thế. */
    Optional<DocumentStatusDetail> findStatusByCodeAndOwner(String code, String accountCode);

    /**
     * Đọc head đã có bản nộp của chính chủ, một mục mỗi loại, thứ tự tên loại tăng dần.
     * Hai con trỏ được đọc trong cùng câu SQL; không suy active từ bản nộp mới nhất hay ngày hết hạn.
     */
    List<DocumentHeadSummary> listHeadsByOwner(String accountCode);
}
