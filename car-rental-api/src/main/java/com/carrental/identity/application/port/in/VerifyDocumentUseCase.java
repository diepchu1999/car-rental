package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.VerifyDocumentCommand;
import com.carrental.identity.application.view.DocumentStatusDetail;

/** BR-130: ADMIN hoặc DOCUMENT_VERIFIER xác minh đúng phiên bản giấy tờ theo mã GT. */
public interface VerifyDocumentUseCase {
    /** Kiểm quyền, lấy người/thời điểm duyệt từ server, ghi và đọc lại trạng thái trong một transaction. */
    DocumentStatusDetail verify(VerifyDocumentCommand command);
}
