package com.carrental.shared.logging;

import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.appender.SingleLineFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm cầu nối appender không bị P6Spy thay mất caller formatter sau khi khởi tạo. */
class SqlCallerLoggerTest {
    /** Giả đúng setter P6Spy gọi; trước/sau đều giữ comment nhiều dòng, caller và số ms. */
    @Test
    void retainsCallerFormatterWhenP6SpySetsDefaultStrategy() {
        var logger = new SqlCallerLogger();
        try (var capture = new LogEventCapture("p6spy")) {
            logger.logSQL(1, "", 3, Category.STATEMENT, "", "-- Fake probe\nSELECT 1", "");
            logger.setStrategy(new SingleLineFormat());
            logger.logSQL(1, "", 4, Category.STATEMENT, "", "-- Fake probe\nSELECT 2", "");
            assertEquals(2, capture.events().size());
            assertEquals("SQL (3 ms):\ncaller: -\n-- Fake probe\nSELECT 1",
                    capture.events().getFirst().getFormattedMessage());
            assertEquals("SQL (4 ms):\ncaller: -\n-- Fake probe\nSELECT 2",
                    capture.events().getLast().getFormattedMessage());
        }
    }
}
