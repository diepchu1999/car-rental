package com.carrental.audit.application.port.out;

import com.carrental.audit.domain.AuditEvent;

/** Cổng persistence chỉ thêm, không nhận read view theo R4. */
public interface WriteAuditEventPort {
    /** Thêm đúng một event trong transaction của use case; không bỏ qua lỗi ghi. */
    void insert(AuditEvent event);
}
