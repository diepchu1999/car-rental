package com.carrental.shared.logging;

import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import com.carrental.shared.sql.SensitiveSql;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Định dạng SQL local theo security-guideline §3; không dùng ở môi trường có dữ liệu thật.
 * Chỉ appender được chọn bởi profile sql-log tạo formatter này; không phải Spring component.
 * SQL thường giữ nguyên xuống dòng; SQL giấy tờ trong SensitiveSql không in nội dung/giá trị.
 */
public final class SqlCallerFormatter implements MessageFormattingStrategy {
    private static final String APPLICATION_PREFIX = "com.carrental.";
    private static final int MAX_FRAMES = 12;
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** Tạo formatter không giữ dữ liệu SQL hoặc context của request trước. */
    public SqlCallerFormatter() {
    }

    /**
     * P6Spy cung cấp sql đã thay tham số; SQL nhạy cảm che cả prepared để không lọt literal/comment.
     * Không in URL JDBC, connection ID hoặc dữ liệu HTTP; requestId/api do MDC của logger cung cấp.
     * SQL rỗng không cần đi bộ stack hoặc sinh nội dung caller.
     */
    @Override
    public String formatMessage(int connectionId, String now, long elapsed, String category,
                                String prepared, String sql, String url) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String caller = WALKER.walk(frames -> formatCaller(frames.map(StackWalker.StackFrame::toStackTraceElement)));
        return "SQL (" + elapsed + " ms):\ncaller: " + caller + "\n"
                + (SensitiveSql.isActive() ? "[REDACTED sensitive SQL]" : sql);
    }

    /**
     * Giữ thứ tự trong-ra-ngoài; áp giới hạn sau khi bỏ frame không liên quan.
     * Đọc thêm một frame để báo cắt ngắn, không thu toàn bộ stack và không loại lặp đệ quy.
     * Chỉ bỏ tiền tố com.carrental., giữ module/package để phân biệt class trùng tên.
     */
    static String formatCaller(Stream<StackTraceElement> frames) {
        List<StackTraceElement> selected = frames.filter(SqlCallerFormatter::isApplicationCaller)
                .limit(MAX_FRAMES + 1).toList();
        if (selected.isEmpty()) {
            return "-";
        }
        String caller = selected.stream().limit(MAX_FRAMES).map(SqlCallerFormatter::formatFrame)
                .collect(Collectors.joining(" ← "));
        return selected.size() > MAX_FRAMES ? caller + " ← [truncated]" : caller;
    }

    /** Loại framework, lớp sinh động proxy/CGLIB và chính hạ tầng ghi log của ứng dụng. */
    private static boolean isApplicationCaller(StackTraceElement frame) {
        String name = frame.getClassName();
        return name.startsWith(APPLICATION_PREFIX)
                && !name.startsWith(APPLICATION_PREFIX + "shared.logging.")
                && !name.equals(APPLICATION_PREFIX + "shared.sql.SensitiveSql")
                && !name.equals(APPLICATION_PREFIX + "shared.config.SchedulerLoggingConfiguration")
                && !name.contains("$$") && !name.contains("$Proxy")
                && !frame.getMethodName().startsWith("CGLIB$");
    }

    /** Hiển thị vị trí source thật khi có, không bịa số dòng nếu bytecode thiếu thông tin debug. */
    private static String formatFrame(StackTraceElement frame) {
        String location = frame.isNativeMethod() ? "Native Method"
                : frame.getFileName() == null ? "Unknown Source"
                : frame.getLineNumber() < 0 ? frame.getFileName()
                : frame.getFileName() + ":" + frame.getLineNumber();
        return frame.getClassName().substring(APPLICATION_PREFIX.length()) + "." + frame.getMethodName()
                + "(" + location + ")";
    }
}
