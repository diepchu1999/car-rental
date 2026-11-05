package com.carrental.identity.application.port.out;

import com.carrental.identity.application.view.DocumentSubmissionDetail;
import com.carrental.identity.application.view.ActiveDocumentDetail;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentReviewDetail;
import com.carrental.identity.application.view.PendingDocumentListItem;

import java.util.List;
import java.util.Optional;

/** Cổng đọc bản nộp BR-130; tách rõ đường đúng chủ và đường phục vụ use case xác minh có kiểm quyền. */
public interface ReadDocumentSubmissionPort {
    /**
     * BR-130: chỉ đọc mã chủ bất biến để use case chặn tự xác minh trước khi ghi quyết định.
     * Không đọc trạng thái để phân xử tranh chấp, số giấy tờ hoặc ảnh; mã không tồn tại trả rỗng.
     */
    Optional<String> findOwnerCodeByCode(String code);

    /**
     * BR-130: metadata các bản active VERIFIED của đúng tài khoản, đọc hai loại trong cùng một snapshot.
     * Dành cho cổng điều kiện thuê nội bộ; caller của cổng chịu trách nhiệm chọn tài khoản đúng ngữ cảnh.
     * Không có active thì không có mục tương ứng; chưa có hồ sơ/không có tài khoản đều trả danh sách rỗng.
     * Không lọc hạn hoặc thay bằng latest/lịch sử; application phân biệt chưa xác minh với hết hạn.
     * Không mở ảnh/giải mã/audit lượt xem. Danh sách bất biến, thứ tự tên loại tăng dần.
     */
    List<ActiveDocumentDetail> listActiveByOwner(String accountCode);

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
