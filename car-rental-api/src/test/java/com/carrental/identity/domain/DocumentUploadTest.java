package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: lượt tải chỉ giữ key, chủ, loại/mặt và hạn đã chốt; không tự quyết TTL. */
class DocumentUploadTest {
    private static final Instant AT = Instant.parse("2026-10-23T03:00:00Z");
    private static final String KEY = "incoming/11111111-1111-1111-1111-111111111111";

    /** Không tính lại hạn hay làm lộ key khi in record. */
    @Test
    void retainsIdentityAndExactLifetimeWithoutLeakingInToString() {
        var upload = new DocumentUpload("AN-ABC123", "TK-ABC123", DocumentType.IDENTITY_CARD,
                DocumentSide.FRONT, KEY, AT, AT.plusSeconds(47));
        assertEquals(AT.plusSeconds(47), upload.expiresAt());
        assertEquals(KEY, upload.incomingObjectKey());
        assertEquals("DocumentUpload[REDACTED]", upload.toString());
    }

    /** Mã/key sai hoặc thời gian không dương không đi tới persistence, lỗi không phản chiếu input. */
    @Test
    void rejectsInvalidShapeWithoutEchoingValues() {
        var failure = assertThrows(IllegalArgumentException.class, () -> new DocumentUpload(
                "AN-ABC123", "TK-ABC123", DocumentType.IDENTITY_CARD, DocumentSide.FRONT,
                "secret-invalid-key", AT, AT.plusSeconds(1)));
        assertFalse(failure.getMessage().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentUpload("AN-ABC123", "TK-ABC123",
                DocumentType.DRIVER_LICENSE, DocumentSide.BACK, KEY, AT, AT));
        assertThrows(IllegalArgumentException.class, () -> new DocumentUpload("bad", "TK-ABC123",
                DocumentType.DRIVER_LICENSE, DocumentSide.BACK, KEY, AT, AT.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentUpload("AN-ABC123", "bad",
                DocumentType.DRIVER_LICENSE, DocumentSide.BACK, KEY, AT, AT.plusSeconds(1)));
    }
}
