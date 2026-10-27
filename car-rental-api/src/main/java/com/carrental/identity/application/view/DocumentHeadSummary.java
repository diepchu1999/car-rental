package com.carrental.identity.application.view;

import com.carrental.identity.domain.DocumentType;

/**
 * BR-130: tách bản nộp mới nhất với bản VERIFIED được head chọn; active có thể null.
 * Active vẫn được trả khi hết hạn để hiển thị lịch sử chính xác, không đồng nghĩa đủ điều kiện thuê.
 * Không tìm lùi bản VERIFIED khác thay thế; cổng điều kiện thuê kiểm hạn ở bước riêng.
 */
public record DocumentHeadSummary(DocumentType documentType, DocumentStatusDetail latestSubmission,
        DocumentStatusDetail activeVerifiedSubmission) {
    /** Không để các view lồng nhau hoặc ghi chú từ chối lọt vào log. */
    @Override public String toString() { return "DocumentHeadSummary[redacted]"; }
}
