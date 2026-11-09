package com.carrental.identity.application.view;

import java.util.List;

/** Trang metadata hàng chờ; hasMore chỉ phản ánh snapshot lần đọc, không đặt chỗ quyền xác minh. */
public record PendingDocumentsPage(List<PendingDocumentListItem> items, boolean hasMore) {
    /** Chụp danh sách bất biến để không bị thay sau khi đã xác định hasMore. */
    public PendingDocumentsPage { items = List.copyOf(items); }

    /** Không in cả danh sách mã hồ sơ/tài khoản khi log page. */
    @Override public String toString() { return "PendingDocumentsPage[redacted]"; }
}
