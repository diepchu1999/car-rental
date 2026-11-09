package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.SubmitDocumentCommand;
import com.carrental.identity.application.view.DocumentSubmissionDetail;

/** BR-120/130: khách nộp giấy tờ của chính mình; danh tính lấy từ caller đã xác thực. */
public interface SubmitDocumentUseCase {
    /** Kiểm cả hai AN, chốt ảnh, bảo vệ số, ghi nguyên tử và đọc lại kết quả trước khi trả. */
    DocumentSubmissionDetail submit(SubmitDocumentCommand command);
}
