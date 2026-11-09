package com.carrental.audit.application.service;

import com.carrental.audit.application.command.RecordDocumentViewCommand;
import com.carrental.audit.application.port.out.WriteAuditEventPort;
import com.carrental.audit.domain.AuditEvent;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối audit không nuốt lỗi hay tự coi việc ghi thất bại là thành công. */
class AuditCommandServiceTest {
    /** Clock của máy chủ quyết định thời điểm, không nhận timestamp người gọi tự khai. */
    @Test void usesServerClock() {
        var write = mock(WriteAuditEventPort.class);
        Instant at = Instant.parse("2026-10-22T03:00:00Z");
        var service = new AuditCommandService(write, Clock.fixed(at, ZoneOffset.UTC));
        service.record(new RecordDocumentViewCommand("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", "request-1"));
        verify(write).insert(new AuditEvent("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", at, "request-1"));
    }

    /** Ghi lỗi phải thoát ra để use case mở ảnh không được trả URL. */
    @Test void propagatesAuditWriteFailure() {
        var write = mock(WriteAuditEventPort.class);
        var expected = new IllegalStateException("Audit unavailable.");
        doThrow(expected).when(write).insert(any());
        var service = new AuditCommandService(write, Clock.systemUTC());
        assertSame(expected, assertThrows(IllegalStateException.class, () -> service.record(
                new RecordDocumentViewCommand("TK-ACTOR1", "TK-OWNER1", "GT-DOC001", "request-1"))));
    }
}
