package com.carrental.identity.application.port.in;

import com.carrental.identity.application.query.GetDocumentStatusQuery;
import com.carrental.identity.application.view.DocumentStatusDetail;

/** BR-130: khách đọc trạng thái một phiên bản giấy tờ của chính mình. */
public interface GetDocumentStatusUseCase {
    /** Sai chủ và không có mã cùng báo DOCUMENT_NOT_FOUND, không cho dò hồ sơ người khác. */
    DocumentStatusDetail get(GetDocumentStatusQuery query);
}
