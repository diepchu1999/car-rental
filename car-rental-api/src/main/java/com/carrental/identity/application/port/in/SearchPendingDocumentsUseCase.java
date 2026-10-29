package com.carrental.identity.application.port.in;

import com.carrental.identity.application.query.SearchPendingDocumentsQuery;
import com.carrental.identity.application.view.PendingDocumentsPage;

/** BR-130/803: hàng chờ xác minh cấp công ty, quyền bắt buộc được kiểm bên trong use case. */
public interface SearchPendingDocumentsUseCase {
    /** Đọc metadata theo trang; không mở ảnh, giải mã số, nhận việc hoặc thay trạng thái. */
    PendingDocumentsPage search(SearchPendingDocumentsQuery query);
}
