package com.carrental.shared.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Thu sự kiện log trong test; chụp MDC ngay trên thread phát log trước khi filter/decorator dọn nó. */
public final class LogEventCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Logger logger;
    private final ConcurrentLinkedQueue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

    /** Gắn bộ thu vào logger được chọn, không đổi mức log hoặc hành vi appender có sẵn. */
    public LogEventCapture(String loggerName) {
        logger = (Logger) LoggerFactory.getLogger(loggerName);
        setContext(logger.getLoggerContext());
        start();
        logger.addAppender(this);
    }

    /** Chốt MDC và thông điệp trên worker thật, rồi công bố sự kiện cho thread test. */
    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    /** Trả snapshot để test đọc an toàn khi scheduler vẫn đang chạy. */
    public List<ILoggingEvent> events() {
        return List.copyOf(events);
    }

    /** Gỡ bộ thu sau test để không rò appender sang lớp khác. */
    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
