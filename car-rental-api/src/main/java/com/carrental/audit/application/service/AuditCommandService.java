package com.carrental.audit.application.service;

import com.carrental.audit.application.command.RecordDocumentViewCommand;
import com.carrental.audit.application.port.in.RecordDocumentViewUseCase;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.audit.domain.AuditEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Objects;

/** BR-130: lượt xem phải có audit đã commit, không mất theo transaction đọc của caller. */
@Service
class AuditCommandService implements RecordDocumentViewUseCase {
    private final WriteAuditEventPort write;
    private final Clock clock;

    /** Nhận thời gian máy chủ và cổng ghi, không phụ thuộc HTTP/MDC/JDBC ở application. */
    AuditCommandService(WriteAuditEventPort write, Clock clock) { this.write = write; this.clock = clock; }

    /** Transaction riêng đảm bảo caller không thể trả URL rồi rollback mất event. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(RecordDocumentViewCommand command) {
        Objects.requireNonNull(command, "command is required.");
        write.insert(new AuditEvent(command.actorAccountCode(), command.subjectAccountCode(),
                command.documentCode(), clock.instant(), command.requestId()));
    }
}
