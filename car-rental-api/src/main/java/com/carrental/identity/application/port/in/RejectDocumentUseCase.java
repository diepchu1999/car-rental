package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.view.DocumentStatusDetail;

/** BR-130: người có quyền từ chối đúng phiên bản, giữ bản VERIFIED đang dùng của khách. */
public interface RejectDocumentUseCase {
    /** Lưu lý do/ghi chú và trả trạng thái đã đọc lại; không giải mã số hoặc tạo liên kết ảnh. */
    DocumentStatusDetail reject(RejectDocumentCommand command);
}
