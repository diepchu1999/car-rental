package com.carrental.identity.adapter.in.rest.client.response;

import com.carrental.identity.application.view.DocumentHeadSummary;

/** BR-130: bản mới đang chờ hoặc từ chối không thay thế bản VERIFIED được chọn. */
public record DocumentHeadResponse(String documentType, DocumentStatusResponse latestSubmission,
        DocumentStatusResponse activeVerifiedSubmission) {
    /** Active null khi chưa có bản được chọn; không tự lọc hạn hoặc biến null thành hồ sơ giả. */
    public static DocumentHeadResponse fromDomain(DocumentHeadSummary view) {
        return new DocumentHeadResponse(view.documentType().name(), DocumentStatusResponse.fromDomain(view.latestSubmission()),
                view.activeVerifiedSubmission() == null ? null : DocumentStatusResponse.fromDomain(view.activeVerifiedSubmission()));
    }

    /** Che cả các DTO lồng nhau để ghi chú không lọt vào log. */
    @Override public String toString() { return "DocumentHeadResponse[redacted]"; }
}
