package com.carrental.booking.application.service;

import com.carrental.booking.application.query.BookingPagePosition;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm định dạng cursor độc lập: chỉ mốc tạo, dấu chủ và mã nghiệp vụ, không có ID số. */
class BookingCursorCodecTest {
    private static final String OWNER = "TK-OWNER1";
    private static final BookingPagePosition POSITION = new BookingPagePosition(
            Instant.parse("2026-11-10T03:00:00.123456Z"), "DT-2611-ABC123");

    /** Giải hết từng trường để chứng minh không có phần ID số được giấu sau mã. */
    @Test
    void roundTripsMicrosecondsAndContainsOnlyDocumentedFields() {
        var cursor = BookingCursorCodec.encode(POSITION, OWNER);
        assertEquals(POSITION, BookingCursorCodec.decode(cursor, OWNER));
        var data = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
        assertEquals(59, data.remaining());
        assertEquals(1, data.get());
        data.position(data.position() + 32);
        assertEquals(POSITION.createdAt().getEpochSecond(), data.getLong());
        assertEquals(POSITION.createdAt().getNano(), data.getInt());
        var code = new byte[14];
        data.get(code);
        assertEquals(POSITION.code(), new String(code, StandardCharsets.US_ASCII));
        assertFalse(data.hasRemaining());
        assertNull(BookingCursorCodec.decode(null, OWNER));
    }

    /** Sai chủ, phiên bản hoặc Base64 đều bị chặn trước truy vấn. */
    @Test
    void rejectsWrongOwnerVersionAndMalformedEncoding() {
        var cursor = BookingCursorCodec.encode(POSITION, OWNER);
        assertInvalid(cursor, "TK-OTHER1");
        for (var value : new String[]{"", " ", "invalid", cursor + "=", cursor.substring(1)}) {
            assertInvalid(value, OWNER);
        }
        var bytes = Base64.getUrlDecoder().decode(cursor);
        bytes[0] = 2;
        assertInvalid(encode(bytes), OWNER);
        bytes[0] = 1;
        bytes[bytes.length - 1] = '!';
        assertInvalid(encode(bytes), OWNER);
    }

    /** Không cho Instant tự chuẩn hoá nano hỏng hoặc PostgreSQL làm tròn cursor. */
    @Test
    void rejectsInvalidTimestampPrecisionAndRange() {
        for (int nanos : new int[]{-1, 1, 1_000_000_000}) {
            var bytes = Base64.getUrlDecoder().decode(BookingCursorCodec.encode(POSITION, OWNER));
            ByteBuffer.wrap(bytes).putInt(41, nanos);
            assertInvalid(encode(bytes), OWNER);
        }
        var bytes = Base64.getUrlDecoder().decode(BookingCursorCodec.encode(POSITION, OWNER));
        ByteBuffer.wrap(bytes).putLong(33, Long.MAX_VALUE);
        assertInvalid(encode(bytes), OWNER);
        assertThrows(IllegalArgumentException.class,
                () -> new BookingPagePosition(POSITION.createdAt(), "DT-2600-ABC123"));
    }

    /** Mã hoá byte đã sửa để kiểm nhánh từ chối của decoder. */
    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lỗi cursor phải giữ mã công khai chung, không lộ chi tiết nội bộ. */
    private static void assertInvalid(String cursor, String owner) {
        var failure = assertThrows(DomainException.class, () -> BookingCursorCodec.decode(cursor, owner));
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
    }
}
