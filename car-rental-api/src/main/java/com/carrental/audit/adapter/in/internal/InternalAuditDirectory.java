package com.carrental.audit.adapter.in.internal;

import com.carrental.audit.api.AuditDirectory;
import com.carrental.audit.application.command.RecordDocumentViewCommand;
import com.carrental.audit.application.port.in.RecordDocumentViewUseCase;
import org.springframework.stereotype.Component;

/** Biên cross-module, chuyển yêu cầu thành command và đi qua proxy transaction của use case. */
@Component
class InternalAuditDirectory implements AuditDirectory {
    private final RecordDocumentViewUseCase useCase;

    /** Không gọi thẳng persistence, giữ đúng chiều Hexagonal. */
    InternalAuditDirectory(RecordDocumentViewUseCase useCase) { this.useCase = useCase; }

    /** Ghi một event cho mỗi lần gọi, không cache hoặc gộp theo requestId. */
    @Override
    public void recordDocumentView(String actorAccountCode, String subjectAccountCode,
                                    String documentCode, String requestId) {
        useCase.record(new RecordDocumentViewCommand(actorAccountCode, subjectAccountCode, documentCode, requestId));
    }
}
