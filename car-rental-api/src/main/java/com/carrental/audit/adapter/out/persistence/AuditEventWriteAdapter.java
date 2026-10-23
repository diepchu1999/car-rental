package com.carrental.audit.adapter.out.persistence;

import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.audit.domain.AuditEvent;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Native SQL append-only, không nuốt lỗi để caller không mở ảnh khi audit chưa được ghi. */
@Repository
class AuditEventWriteAdapter implements WriteAuditEventPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String insertSql;

    /** Tải SQL một lần từ tài nguyên module audit. */
    AuditEventWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        this.insertSql = loader.load("sql/audit/insert_event.sql");
    }

    /** Kiểm số dòng ghi, không có ON CONFLICT bỏ mất một lượt mở. */
    @Override
    public void insert(AuditEvent event) {
        int count = jdbc.update(insertSql, Map.of("actor", event.actorAccountCode(), "action", event.action(),
                "subject", event.subjectAccountCode(), "resource", event.resourceCode(),
                "at", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC), "requestId", event.requestId()));
        if (count != 1) throw new IllegalStateException("Audit insert did not write exactly one event.");
    }
}
