package com.carrental.audit.application.port.in;

import com.carrental.audit.application.command.RecordDocumentViewCommand;

/** Use case ghi nhật ký mở giấy tờ, BR-130. */
public interface RecordDocumentViewUseCase {
    /** Trở về chỉ sau khi event đã commit; thất bại phải chặn cấp URL. */
    void record(RecordDocumentViewCommand command);
}
