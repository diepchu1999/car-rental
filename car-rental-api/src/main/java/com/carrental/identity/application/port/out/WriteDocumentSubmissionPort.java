package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.DocumentSubmission;
import com.carrental.identity.application.command.RejectDocumentCommand;

import java.time.Instant;

/** BR-130: ghi bản nộp và quyết định từ chối; cả hai thao tác giữ phiên bản VERIFIED đang dùng. */
public interface WriteDocumentSubmissionPort {
    /**
     * Bắt buộc nằm trong transaction của use case. Khoá head theo chủ/loại, thay PENDING cũ thành
     * SUPERSEDED, ghi bản mới + ảnh rồi cập nhật latest; giữ active nguyên trạng.
     * Chỉ trùng mã GT trả false và không để lại thay đổi; mọi lỗi khác rollback savepoint rồi ném ra.
     * Một AN đã dùng trả DOCUMENT_UPLOAD_ALREADY_USED, không coi là trùng GT để thử mã khác.
     */
    boolean insert(DocumentSubmission submission);

    /**
     * BR-130: từ chối latest còn PENDING trong transaction của use case; khoá cùng head với nộp lại.
     * Giữ nguyên active VERIFIED. Không có hồ sơ trả DOCUMENT_NOT_FOUND; đã xử lý/bị thay trả
     * DOCUMENT_REVIEW_CONFLICT, không thử lại. Người duyệt và thời điểm phải do use case cung cấp.
     * Adapter hoàn tác savepoint khi lỗi; use case chịu trách nhiệm kiểm vai trước khi gọi port.
     */
    void reject(RejectDocumentCommand command, String reviewerAccountCode, Instant reviewedAt);
}
