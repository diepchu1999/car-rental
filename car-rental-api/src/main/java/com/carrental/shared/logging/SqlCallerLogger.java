package com.carrental.shared.logging;

import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import com.p6spy.engine.spy.appender.Slf4JLogger;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;

/**
 * Cầu nối cấu hình starter P6Spy với formatter caller, chỉ được chọn trong application-sql-log.yml.
 * Giữ nguyên logger p6spy, mức log và MDC của Slf4JLogger; không tự quản lý system property của JVM.
 */
public final class SqlCallerLogger extends Slf4JLogger {
    private final MessageFormattingStrategy callerFormatter = new SqlCallerFormatter();

    /** Có constructor public không tham số để P6Spy tạo trước truy vấn đầu tiên, kể cả Flyway. */
    public SqlCallerLogger() {
        super.setStrategy(callerFormatter);
    }

    /**
     * P6Spy gọi setter sau khi tạo appender và khi cập nhật cấu hình formatter.
     * Appender chuyên biệt luôn dùng caller formatter; không để SingleLineFormat mặc định thay thế
     * làm mất xuống dòng SQL hoặc caller. Không ảnh hưởng appender khác khi profile này tắt.
     */
    @Override
    public void setStrategy(MessageFormattingStrategy ignored) {
        super.setStrategy(callerFormatter);
    }

    /** Đường lỗi riêng của P6Spy cũng không được in exception JDBC thô của giấy tờ. */
    @Override
    public void logException(Exception failure) {
        super.logException(SensitiveSql.isActive() ? SensitiveSqlFailure.from(failure) : failure);
    }

    /** Che cả thông điệp ngoài logSQL khi đang trong phạm vi nhạy cảm. */
    @Override
    public void logText(String text) {
        super.logText(SensitiveSql.isActive() ? "[REDACTED sensitive SQL]" : text);
    }
}
