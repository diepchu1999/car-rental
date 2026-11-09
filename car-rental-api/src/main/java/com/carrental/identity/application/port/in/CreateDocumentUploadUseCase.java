package com.carrental.identity.application.port.in;

import com.carrental.identity.application.command.CreateDocumentUploadCommand;
import com.carrental.identity.application.view.DocumentUploadDetail;

/** BR-120/130: cấp lượt tải chỉ cho khách đã đăng ký và đồng ý điều khoản. */
public interface CreateDocumentUploadUseCase {
    /** Lưu đúng chủ/loại/mặt; trả URL sau transaction, không nhận danh tính từ command. */
    DocumentUploadDetail create(CreateDocumentUploadCommand command);
}
