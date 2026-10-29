package com.carrental.identity.adapter.in.rest.admin.response;

import com.carrental.identity.application.view.PendingDocumentsPage;
import java.util.List;

/** Trang hàng chờ admin; hasMore phản ánh lần đọc hiện tại, không giữ chỗ xác minh. */
public record PendingDocumentsResponse(List<PendingDocumentResponse> items, boolean hasMore) {
    /** Giữ danh sách bất biến trong suốt quá trình tạo response. */
    public PendingDocumentsResponse { items = List.copyOf(items); }

    /** Ánh xạ từng item qua DTO riêng, không trả view application nguyên trạng. */
    public static PendingDocumentsResponse fromDomain(PendingDocumentsPage page) {
        return new PendingDocumentsResponse(page.items().stream().map(PendingDocumentResponse::fromDomain).toList(), page.hasMore());
    }

    /** Không in danh sách hồ sơ vào log khi logger nhận cả trang. */
    @Override public String toString() { return "PendingDocumentsResponse[redacted]"; }
}
