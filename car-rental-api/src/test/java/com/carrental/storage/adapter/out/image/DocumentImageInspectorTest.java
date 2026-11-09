package com.carrental.storage.adapter.out.image;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.storage.DocumentImageFixtures;
import com.carrental.storage.domain.DocumentImagePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** BR-130: kiểm parser thật, biên byte/pixel và file giả mạo, không dùng extension/MIME đầu vào. */
class DocumentImageInspectorTest {
    private final DocumentImageInspector inspector = new DocumentImageInspector();
    private final DocumentImagePolicy policy = limits(5000000, 40000000);

    /** Hai loại ảnh tự tạo được nhận diện và giải mã đúng. */
    @ParameterizedTest @CsvSource({"png,image/png", "jpeg,image/jpeg"})
    void recognizesRealJpegAndPng(String format, String expected) {
        var metadata = inspector.inspect(DocumentImageFixtures.image(format), policy);
        assertEquals(expected, metadata.contentType());
        assertEquals(16, metadata.width());
        assertEquals(12, metadata.height());
    }

    /** HEIC thật không phải JPEG đổi đuôi; đọc kích thước container, không decode HEVC. */
    @Test void recognizesRealHeicContainer() {
        var metadata = inspector.inspect(DocumentImageFixtures.heic(), policy);
        assertEquals("image/heic", metadata.contentType());
        assertEquals(2, metadata.width());
        assertEquals(2, metadata.height());
    }

    /** Văn bản giả jpg, GIF và file rỗng bị từ chối; không phản chiếu nội dung vào exception. */
    @Test void rejectsTextUnsupportedImageAndEmptyContent() {
        for (byte[] bytes : new byte[][] {"private-document-sentinel".getBytes(StandardCharsets.UTF_8),
                DocumentImageFixtures.image("gif"), new byte[0]}) {
            var failure = assertThrows(DomainException.class, () -> inspector.inspect(bytes, policy));
            assertEquals(ErrorCode.DOCUMENT_IMAGE_INVALID, failure.errorCode());
            assertFalse(failure.toString().contains("private-document-sentinel"));
        }
    }

    /** Đúng ngưỡng byte được nhận; vượt một byte bị chặn trước parser. */
    @Test void enforcesInclusiveByteLimit() {
        byte[] png = DocumentImageFixtures.image("png");
        assertNotNull(inspector.inspect(png, limits(png.length, 40000000)));
        assertEquals(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE, assertThrows(DomainException.class,
                () -> inspector.inspect(png, limits(png.length - 1, 40000000))).errorCode());
        assertEquals(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE, assertThrows(DomainException.class,
                () -> inspector.inspect(new byte[6000000], policy)).errorCode());
    }

    /** Pixel đúng ngưỡng được nhận, vượt ngưỡng bị chặn trước giải mã JPEG/PNG. */
    @Test void enforcesInclusivePixelLimit() {
        byte[] png = DocumentImageFixtures.image("png");
        assertNotNull(inspector.inspect(png, limits(png.length, 192)));
        assertEquals(ErrorCode.DOCUMENT_IMAGE_INVALID, assertThrows(DomainException.class,
                () -> inspector.inspect(png, limits(png.length, 191))).errorCode());
    }

    /** Box HEIC bị cắt, brand AVIF, header thiếu ảnh hoặc kích thước phình đều bị từ chối. */
    @Test void rejectsTruncatedSpoofedAndOversizedHeic() {
        byte[] original = DocumentImageFixtures.heic();
        byte[] avif = original.clone();
        for (int i = 0; i < avif.length - 3; i++) {
            if (new String(avif, i, 4, StandardCharsets.US_ASCII).equals("heic"))
                System.arraycopy("avif".getBytes(StandardCharsets.US_ASCII), 0, avif, i, 4);
        }
        byte[] oversized = original.clone();
        int ispe = indexOf(oversized, "ispe");
        ByteBuffer.wrap(oversized, ispe + 8, 8).putInt(100000).putInt(100000);
        for (byte[] bytes : new byte[][] {Arrays.copyOf(original, original.length - 3),
                Arrays.copyOf(original, 24), avif, oversized}) {
            assertEquals(ErrorCode.DOCUMENT_IMAGE_INVALID, assertThrows(DomainException.class,
                    () -> inspector.inspect(bytes, policy)).errorCode());
        }
    }

    /** Hình dạng cấu hình dùng chung cho kiểm biên, không tác động cấu hình production. */
    private static DocumentImagePolicy limits(long bytes, long pixels) {
        return new DocumentImagePolicy(bytes, pixels, Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    /** Tìm field cấu trúc trong fixture thật để tạo biến thể lỗi có chủ đích. */
    private static int indexOf(byte[] bytes, String marker) {
        for (int i = 0; i <= bytes.length - 4; i++)
            if (new String(bytes, i, 4, StandardCharsets.US_ASCII).equals(marker)) return i;
        throw new AssertionError("Missing test box.");
    }
}
