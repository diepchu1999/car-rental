package com.carrental.shared.logging;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Tạo một dòng chẩn đoán nội bộ theo security-guideline §3; không dùng làm nội dung API. */
public final class FailureSummary {

    /** Lớp tiện ích không có trạng thái hoặc đối tượng sử dụng trực tiếp. */
    private FailureSummary() {
    }

    /**
     * Lấy nguyên nhân sâu nhất và frame ứng dụng đầu tiên của chính nguyên nhân đó.
     * Chỉ nhận method/path, không nhận request, body, header, token hoặc query string.
     * Ký tự điều khiển được escape trong dòng tóm tắt; exception gốc không bị sửa.
     */
    public static String format(Throwable failure, @Nullable String method, @Nullable String path) {
        Throwable root = rootCause(failure);
        return "Failure summary: rootType=\"" + escape(root.getClass().getName())
                + "\" rootMessage=\"" + escape(root.getMessage())
                + "\" source=\"" + escape(applicationFrame(root))
                + "\" method=\"" + escape(method)
                + "\" path=\"" + escape(path)
                + "\" requestId=\"" + escape(MDC.get(RequestIdFilter.MDC_KEY))
                + "\" api=\"" + escape(MDC.get(RequestIdFilter.API_MDC_KEY)) + "\"";
    }

    /** Duyệt bằng danh tính đối tượng để chuỗi cause bất thường có vòng lặp không treo handler. */
    private static Throwable rootCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        seen.add(current);
        while (current.getCause() != null && seen.add(current.getCause())) {
            current = current.getCause();
        }
        return current;
    }

    /** Không lấy frame từ wrapper để giả thành vị trí của nguyên nhân gốc; thiếu thì ghi dấu gạch. */
    private static String applicationFrame(Throwable root) {
        for (StackTraceElement frame : root.getStackTrace()) {
            if (frame.getClassName().startsWith("com.carrental.")) {
                String location = frame.isNativeMethod() ? "Native Method"
                        : frame.getFileName() == null ? "Unknown Source"
                        : frame.getFileName() + ":" + frame.getLineNumber();
                return frame.getClassName() + "." + frame.getMethodName() + "(" + location + ")";
            }
        }
        return "-";
    }

    /** Escape dấu phân cách, ký tự điều khiển, định dạng và ngắt dòng Unicode để giữ đúng một dòng. */
    private static String escape(@Nullable String value) {
        return LogValueSanitizer.escape(value);
    }
}
