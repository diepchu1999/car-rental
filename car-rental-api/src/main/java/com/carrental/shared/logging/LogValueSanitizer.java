package com.carrental.shared.logging;

import org.jspecify.annotations.Nullable;

/** Escape giá trị chẩn đoán để không chèn ký tự điều khiển hoặc dòng log giả. */
final class LogValueSanitizer {
    /** Chỉ cung cấp hàm dùng chung trong tầng logging. */
    private LogValueSanitizer() {
    }

    /** Giữ nguyên ký tự thông thường, escape dấu phân cách/control/Unicode format; null dùng dấu gạch. */
    static String escape(@Nullable String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
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
        });
        return result.toString();
    }
}
