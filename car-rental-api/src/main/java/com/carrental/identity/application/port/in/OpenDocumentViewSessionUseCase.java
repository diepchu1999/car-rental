package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.OpenDocumentViewSessionCommand;
import com.carrental.identity.application.view.DocumentViewSessionDetail;

/** BR-130: cửa mở thông tin và ảnh giấy tờ cho người xác minh, mỗi lần mở ghi một audit riêng. */
public interface OpenDocumentViewSessionUseCase {
    /** Chỉ ADMIN/DOCUMENT_VERIFIER; không trả dữ liệu hay URL nếu audit chưa commit thành công. */
    DocumentViewSessionDetail open(OpenDocumentViewSessionCommand command);
}
