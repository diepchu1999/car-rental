package com.carrental.shared.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Security-guideline §3: nhãn API hữu hạn, không chèn dòng giả hoặc cắt dở Unicode/escape. */
class LogValueSanitizerTest {
    /** Dưới hoặc đúng 512 giữ nguyên toàn bộ, không cắt sớm chỉ để chừa chỗ cho dấu. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 500, 501, 511, 512})
    void preservesValuesWithinLimit(int length) {
        String value = "a".repeat(length);
        assertEquals(value, LogValueSanitizer.escapeApi(value));
    }

    /** Vượt một ký tự hay rất nhiều đều chặn ở 512, dấu cắt nằm trong giới hạn. */
    @ParameterizedTest
    @ValueSource(ints = {513, 1024, 100000})
    void truncatesOversizedValuesWithMarkerInsideLimit(int length) {
        String result = LogValueSanitizer.escapeApi("a".repeat(length));
        assertEquals("a".repeat(501) + "[truncated]", result);
        assertEquals(512, result.length());
    }

    /** Đo sau escape: 256 LF vừa đủ 512, thêm một LF thì phải cắt tại cặp escape hoàn chỉnh. */
    @Test
    void appliesLimitAfterEscaping() {
        assertEquals("\\n".repeat(256), LogValueSanitizer.escapeApi("\n".repeat(256)));
        String result = LogValueSanitizer.escapeApi("\n".repeat(257));
        assertEquals("\\n".repeat(250) + "[truncated]", result);
        assertEquals(511, result.length());
        assertFalse(result.contains("\n"));
    }

    /** Dấu quote/backslash/control và Unicode format không bị cắt giữa chuỗi escape. */
    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\t", "\u001b", "\u2028", "\u202e", "\\", "\"", "\uDB40\uDC01"})
    void neverSplitsEscapeAtTruncationBoundary(String value) {
        assertEquals("a".repeat(500) + "[truncated]",
                LogValueSanitizer.escapeApi("a".repeat(500) + value + "z".repeat(20)));
    }

    /** Emoji được giữ nguyên cặp surrogate khi vừa chỗ và bỏ nguyên code point khi cần cắt. */
    @Test
    void neverSplitsSupplementaryUnicode() {
        String emoji = "\uD83D\uDE97";
        assertEquals("a".repeat(510) + emoji, LogValueSanitizer.escapeApi("a".repeat(510) + emoji));
        assertEquals("a".repeat(500) + "[truncated]",
                LogValueSanitizer.escapeApi("a".repeat(500) + emoji + "z".repeat(20)));
        assertEquals("a".repeat(499) + emoji + "[truncated]",
                LogValueSanitizer.escapeApi("a".repeat(499) + emoji + "z".repeat(20)));
    }

    /** Nhãn ngắn vẫn escape đầy đủ như trước; dữ liệu null vẫn dùng dấu gạch. */
    @Test
    void preservesExistingEscapingRules() {
        String input = "GET /probe\r\n\t\"\\\u001b\u2028\u202e";
        String expected = "GET /probe\\r\\n\\t\\\"\\\\\\u001b\\u2028\\u202e";
        assertEquals(expected, LogValueSanitizer.escapeApi(input));
        assertEquals(expected, LogValueSanitizer.escape(input));
        assertEquals("-", LogValueSanitizer.escapeApi(null));
        assertEquals("-", LogValueSanitizer.escape(null));
    }

    /** Giới hạn chỉ dành cho api; không âm thầm cắt thông điệp exception đang dùng escape chung. */
    @Test
    void doesNotTruncateGeneralDiagnosticEscaping() {
        assertEquals("a".repeat(1024) + "\\n", LogValueSanitizer.escape("a".repeat(1024) + "\n"));
    }
}
