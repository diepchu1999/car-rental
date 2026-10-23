package com.carrental.shared.sql;

import java.util.function.Supplier;

/**
 * Security-guideline §3: phạm vi JDBC đồng bộ có dữ liệu giấy tờ, không đưa giá trị vào log.
 * Không mở transaction, không đổi SQL hoặc kết quả; không truyền context sang thread con.
 */
public final class SensitiveSql {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    /** Tiện ích không có instance hoặc trạng thái dữ liệu người dùng. */
    private SensitiveSql() { }

    /** Chỉ là cờ kỹ thuật để formatter/filter che log, không giữ tham số hoặc kết quả SQL. */
    public static boolean isActive() { return Boolean.TRUE.equals(ACTIVE.get()); }

    /**
     * Bao đúng thao tác JDBC và đọc row; lỗi ra ngoài đã bỏ message/cause/suppressed nhạy cảm.
     * Khôi phục phạm vi ngoài kể cả khi callback lỗi; không tự thử lại hoặc nuốt lỗi lưu trữ.
     */
    public static <T> T execute(Supplier<T> operation) {
        boolean previous = isActive();
        ACTIVE.set(true);
        try {
            return operation.get();
        } catch (SensitiveSqlFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw SensitiveSqlFailure.from(failure);
        } finally {
            if (previous) ACTIVE.set(true);
            else ACTIVE.remove();
        }
    }
}
