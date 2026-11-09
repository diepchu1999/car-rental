package com.carrental.shared.sql;

import org.postgresql.util.PSQLException;

import java.io.Serial;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * Lỗi nội bộ đã bỏ toàn bộ payload JDBC theo BR-130; chỉ giữ SQLSTATE, tên constraint và stack.
 * Không giữ tham chiếu tới exception gốc vì message/detail/nextException có thể chứa cả row.
 */
public final class SensitiveSqlFailure extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;
    private final String sqlState;
    private final String constraint;

    /** Chỉ nhận metadata đã lọc, không nhận câu SQL, giá trị bind hoặc exception làm cause. */
    private SensitiveSqlFailure(String type, String sqlState, String constraint, StackTraceElement[] stack) {
        super("Sensitive SQL operation failed (" + type + "; SQLSTATE=" + sqlState
                + "; constraint=" + constraint + ").", null, false, true);
        this.sqlState = sqlState;
        this.constraint = constraint;
        setStackTrace(stack);
    }

    /** Duyệt cause có chống vòng lặp, không đọc message/detail hay gọi toString của exception gốc. */
    public static SensitiveSqlFailure from(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable root = failure;
        SQLException sql = null;
        PSQLException postgres = null;
        while (root != null && seen.add(root)) {
            if (root instanceof SQLException value) sql = value;
            if (root instanceof PSQLException value) postgres = value;
            if (root.getCause() == null || seen.contains(root.getCause())) break;
            root = root.getCause();
        }
        String state = sql == null ? "-" : safe(sql.getSQLState(), "[A-Z0-9]{5}");
        String constraint = postgres == null || postgres.getServerErrorMessage() == null ? "-"
                : safe(postgres.getServerErrorMessage().getConstraint(), "[a-z][a-z0-9_]{0,127}");
        return new SensitiveSqlFailure(root.getClass().getName(), state, constraint, root.getStackTrace());
    }

    /** SQLSTATE phục vụ adapter phân loại lỗi kỹ thuật; không đưa vào response API. */
    public String sqlState() { return sqlState; }

    /** Tên constraint phục vụ đối chiếu chính xác; không suy lỗi nghiệp vụ chỉ từ nhóm SQLSTATE. */
    public String constraint() { return constraint; }

    /** Giới hạn metadata để tên bất thường hoặc ký tự điều khiển không lọt qua log. */
    private static String safe(String value, String pattern) {
        return value != null && value.matches(pattern) ? value : "-";
    }
}
