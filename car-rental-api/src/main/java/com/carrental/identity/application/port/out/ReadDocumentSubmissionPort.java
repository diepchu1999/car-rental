package com.carrental.identity.application.port.out;

import com.carrental.identity.application.view.DocumentSubmissionDetail;

import java.util.Optional;

/** Cổng đọc theo mã GT và chủ BR-130; không có đường đọc giấy tờ tuỳ ý chỉ bằng mã. */
public interface ReadDocumentSubmissionPort {
    /** Chủ lấy từ caller; mã không tồn tại hoặc khác chủ đều trả rỗng, không giải mã số hay ký URL. */
    Optional<DocumentSubmissionDetail> findByCodeAndOwner(String code, String accountCode);
}
