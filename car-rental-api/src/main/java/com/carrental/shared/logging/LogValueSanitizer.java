package com.carrental.shared.logging;

import org.jspecify.annotations.Nullable;

/** Escape giá trị chẩn đoán để không chèn ký tự điều khiển hoặc dòng log giả. */
final class LogValueSanitizer {
    private static final int API_MAX_LENGTH = 512;
    private static final String TRUNCATION_MARKER = "[truncated]";

    /** Chỉ cung cấp hàm dùng chung trong tầng logging. */
    private LogValueSanitizer() {
    }

    /** Giữ nguyên ký tự thông thường, escape dấu phân cách/control/Unicode format; null dùng dấu gạch. */
    static String escape(@Nullable String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(codePoint -> appendEscaped(result, codePoint));
        return result.toString();
    }

    /**
     * Giới hạn nhãn API sau escape ở 512 đơn vị UTF-16, gồm cả dấu [truncated].
     * Chỉ cắt tại ranh giới code point/escape hoàn chỉnh; không dựng toàn bộ chuỗi escape của path dài.
     * Chuỗi đúng giới hạn được giữ nguyên, chỉ thêm dấu khi thật sự bỏ nội dung phía sau.
     */
    static String escapeApi(@Nullable String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder result = new StringBuilder(API_MAX_LENGTH);
        int lastSafeBoundary = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            appendEscaped(result, codePoint);
            if (result.length() > API_MAX_LENGTH) {
                result.setLength(lastSafeBoundary);
                return result.append(TRUNCATION_MARKER).toString();
            }
            if (result.length() <= API_MAX_LENGTH - TRUNCATION_MARKER.length()) {
                lastSafeBoundary = result.length();
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    /** Ghi nguyên một code point hoặc escape tương ứng; dùng chung để hai cách escape không lệch nhau. */
    private static void appendEscaped(StringBuilder result, int codePoint) {
        switch (codePoint) {
            case '\\' -> result.append("\\\\");
            case '"' -> result.append("\\\"");
            case '\r' -> result.append("\\r");
            case '\n' -> result.append("\\n");
            case '\t' -> result.append("\\t");
            default -> {
                int type = Character.getType(codePoint);
                if (Character.isISOControl(codePoint) || type == Character.FORMAT
                        || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                    result.append(codePoint <= 0xffff ? "\\u%04x".formatted(codePoint)
                            : "\\U%08x".formatted(codePoint));
                } else {
                    result.appendCodePoint(codePoint);
                }
            }
        }
    }
}
