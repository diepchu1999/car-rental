package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.DocumentSubmission;

/** BR-130: ghi một phiên bản giấy tờ cùng hai ảnh, không thay phiên bản VERIFIED đang dùng. */
public interface WriteDocumentSubmissionPort {
    /**
     * Bắt buộc nằm trong transaction của use case. Khoá head theo chủ/loại, thay PENDING cũ thành
     * SUPERSEDED, ghi bản mới + ảnh rồi cập nhật latest; giữ active nguyên trạng.
     * Chỉ trùng mã GT trả false và không để lại thay đổi; mọi lỗi khác rollback savepoint rồi ném ra.
     * Một AN đã dùng trả DOCUMENT_UPLOAD_ALREADY_USED, không coi là trùng GT để thử mã khác.
     */
    boolean insert(DocumentSubmission submission);
}
