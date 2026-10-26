package com.carrental.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: cửa port ghi chỉ nhận số đã bảo vệ và đủ hai mặt ảnh có cấu trúc hợp lệ. */
class DocumentSubmissionTest {
    private static final String FRONT_KEY = "sealed/11111111-1111-1111-1111-111111111111";
    private static final String BACK_KEY = "sealed/22222222-2222-2222-2222-222222222222";
    private static final ProtectedDocumentNumber NUMBER = new ProtectedDocumentNumber(1, "nonce", "ciphertext", "hash");

    /** Hai ảnh hợp lệ không khiến domain tự hardcode giới hạn byte/pixel của cấu hình storage. */
    @Test void acceptsSealedImagesAndRedactsData() {
        var front = image("AN-FRONT1", DocumentSide.FRONT, FRONT_KEY);
        var back = image("AN-BACK01", DocumentSide.BACK, BACK_KEY);
        var document = card(front, back);
        assertEquals("DocumentSubmission[redacted]", document.toString());
        assertEquals("DocumentImage[redacted]", front.toString());
        assertEquals(NUMBER, document.number());
        assertDoesNotThrow(() -> new DocumentImage("AN-FRONT1", DocumentSide.FRONT, FRONT_KEY,
                "image/heic", 6_000_000, 10_000, 10_000));
    }

    /** Không cho thiếu/đảo mặt, dùng một AN hai lần hoặc dùng cùng key sealed cho hai mặt. */
    @Test void requiresTwoDistinctImagesWithCorrectSides() {
        var front = image("AN-FRONT1", DocumentSide.FRONT, FRONT_KEY);
        var back = image("AN-BACK01", DocumentSide.BACK, BACK_KEY);
        assertThrows(IllegalArgumentException.class, () -> card(null, back));
        assertThrows(IllegalArgumentException.class, () -> card(front, null));
        assertThrows(IllegalArgumentException.class, () -> card(back, front));
        assertThrows(IllegalArgumentException.class,
                () -> card(front, image("AN-FRONT1", DocumentSide.BACK, BACK_KEY)));
        assertThrows(IllegalArgumentException.class,
                () -> card(front, image("AN-BACK01", DocumentSide.BACK, FRONT_KEY)));
    }

    /** Key incoming/URL/metadata sai không được giả thành tham chiếu ảnh đã chốt. */
    @Test void rejectsInvalidSealedReferencesWithoutEchoingThem() {
        for (String key : new String[]{"incoming/private-key", "https://private-url", "sealed/private-key"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> image("AN-FRONT1", DocumentSide.FRONT, key));
            assertFalse(failure.toString().contains("private"));
        }
        assertThrows(IllegalArgumentException.class, () -> image("GT-FRONT1", DocumentSide.FRONT, FRONT_KEY));
        assertThrows(IllegalArgumentException.class, () -> image("AN-FRONT1", null, FRONT_KEY));
        assertThrows(IllegalArgumentException.class, () -> new DocumentImage("AN-FRONT1", DocumentSide.FRONT,
                FRONT_KEY, "text/plain", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentImage("AN-FRONT1", DocumentSide.FRONT,
                FRONT_KEY, "image/png", 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentImage("AN-FRONT1", DocumentSide.FRONT,
                FRONT_KEY, "image/png", 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DocumentImage("AN-FRONT1", DocumentSide.FRONT,
                FRONT_KEY, "image/png", 1, 1, -1));
    }

    /** Dữ liệu mã/chủ/phong bì và bộ trường phải đúng trước khi vào SQL. */
    @Test void rejectsIncompleteOrMixedSubmissionFields() {
        var front = image("AN-FRONT1", DocumentSide.FRONT, FRONT_KEY);
        var back = image("AN-BACK01", DocumentSide.BACK, BACK_KEY);
        var at = Instant.parse("2026-10-26T03:00:00Z");
        var date = LocalDate.of(2035, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("AN-FRONT1", "TK-OWNER1",
                DocumentType.IDENTITY_CARD, NUMBER, "LOCAL", date, date, null, null, at, front, back));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("GT-DOC001", null,
                DocumentType.IDENTITY_CARD, NUMBER, "LOCAL", date, date, null, null, at, front, back));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("GT-DOC001", "TK-OWNER1",
                DocumentType.IDENTITY_CARD, null, "LOCAL", date, date, null, null, at, front, back));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("GT-DOC001", "TK-OWNER1",
                DocumentType.IDENTITY_CARD, NUMBER, "LOCAL", null, date, null, null, at, front, back));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("GT-DOC001", "TK-OWNER1",
                DocumentType.DRIVER_LICENSE, NUMBER, "LOCAL", null, date, "B", false, at, front, back));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSubmission("GT-DOC001", "TK-OWNER1",
                DocumentType.DRIVER_LICENSE, NUMBER, null, null, date, "B", null, at, front, back));
        assertDoesNotThrow(() -> new DocumentSubmission("GT-DOC001", "TK-OWNER1",
                DocumentType.DRIVER_LICENSE, NUMBER, null, null, date, "B", false, at, front, back));
    }

    /** CCCD nội bộ giả chỉ phục vụ kiểm cấu trúc; phong bì thật được kiểm ở integration/crypto test. */
    private static DocumentSubmission card(DocumentImage front, DocumentImage back) {
        return new DocumentSubmission("GT-DOC001", "TK-OWNER1", DocumentType.IDENTITY_CARD, NUMBER,
                "LOCAL PRIVATE NAME", LocalDate.of(1990, 1, 1), LocalDate.of(2035, 1, 1), null, null,
                Instant.parse("2026-10-26T03:00:00Z"), front, back);
    }

    /** Tham chiếu ảnh giả không có URL ký hoặc bytes. */
    private static DocumentImage image(String code, DocumentSide side, String key) {
        return new DocumentImage(code, side, key, "image/png", 100, 10, 10);
    }
}
