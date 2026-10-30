package com.carrental.identity.application.port.out;

import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentReviewDetail;
import com.carrental.identity.application.view.PendingDocumentListItem;

import java.util.List;
import java.util.Optional;

/** Cổng đọc bản nộp BR-130; tách rõ đường đúng chủ và đường phục vụ use case xác minh có kiểm quyền. */
public interface ReadDocumentSubmissionPort {
    /**
     * Đọc lại kết quả sau quyết định của use case đã kiểm quyền ADMIN/DOCUMENT_VERIFIER.
     * Chỉ metadata trạng thái, không đọc số/nhân thân/ảnh; không phải thao tác mở view-session.
     */
    Optional<DocumentStatusDetail> findStatusForReviewByCode(String code);

    /** Chủ lấy từ caller; mã không tồn tại hoặc khác chủ đều trả rỗng, không giải mã số hay ký URL. */
    Optional<DocumentSubmissionDetail> findByCodeAndOwner(String code, String accountCode);

    /** Đọc cả kết quả xét duyệt của một phiên bản đúng chủ, kể cả phiên bản đã bị thay thế. */
    Optional<DocumentStatusDetail> findStatusByCodeAndOwner(String code, String accountCode);

    /**
     * Đọc head đã có bản nộp của chính chủ, một mục mỗi loại, thứ tự tên loại tăng dần.
     * Hai con trỏ được đọc trong cùng câu SQL; không suy active từ bản nộp mới nhất hay ngày hết hạn.
     */
    List<DocumentHeadSummary> listHeadsByOwner(String accountCode);

    /**
     * Đọc đúng phiên bản theo mã GT, không tự chuyển sang latest/active hoặc giới hạn PENDING.
     * Chỉ use case đã kiểm ADMIN/DOCUMENT_VERIFIER được gọi; phải audit trước khi trả dữ liệu/URL.
     * Không giải mã, không ký URL; mã không tồn tại trả rỗng, dữ liệu hỏng phải ném lỗi nội bộ.
     */
    Optional<DocumentReviewDetail> findForReviewByCode(String code);

    /**
     * Chỉ PENDING, thứ tự submittedAt tăng dần rồi mã GT theo ASCII; không lọc theo chủ hoặc chi nhánh.
     * Use case kiểm quyền trước; fetchLimit gồm tối đa một hàng thăm dò để tính hasMore (1..101), offset >= 0.
     * Chỉ đọc metadata, không lấy số/nhân thân/key ảnh hoặc ghi audit mở hồ sơ.
     */
    List<PendingDocumentListItem> searchPending(int fetchLimit, long offset);
}
