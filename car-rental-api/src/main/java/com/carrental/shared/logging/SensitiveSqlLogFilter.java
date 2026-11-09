package com.carrental.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.carrental.shared.sql.SensitiveSql;
import org.slf4j.Marker;

/** Chặn đường DEBUG/TRACE của JDBC trong phạm vi giấy tờ; P6Spy riêng vẫn có sự kiện đã che. */
public final class SensitiveSqlLogFilter extends TurboFilter {
    /** Không giữ dữ liệu request, quyết định theo thread đang thực thi câu JDBC. */
    public SensitiveSqlLogFilter() { }

    /** Không thay mức log toàn cục; chỉ chặn framework có thể in SQL, bind hoặc chi tiết lỗi thô. */
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                              Object[] parameters, Throwable failure) {
        String name = logger.getName();
        if (SensitiveSql.isActive() && (name.startsWith("org.springframework.jdbc.")
                || name.startsWith("org.postgresql.") || name.startsWith("com.zaxxer.hikari.")
                || name.startsWith("com.p6spy."))) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
