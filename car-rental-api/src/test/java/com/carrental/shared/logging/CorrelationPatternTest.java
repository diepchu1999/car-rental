package com.carrental.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm chính pattern trong YAML: ẩn cụm rỗng mà không cần converter hoặc cấu hình Logback riêng. */
class CorrelationPatternTest {
    /** Render YAML thật với bốn tổ hợp MDC, giữ nguyên message và thứ tự hai nhãn. */
    @ParameterizedTest
    @MethodSource("contexts")
    void rendersOnlyUsefulCorrelationContext(Map<String, String> mdc, String expected) throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        String pattern = sources.stream().map(source -> source.getProperty("logging.pattern.correlation"))
                .filter(java.util.Objects::nonNull).map(Object::toString).findFirst().orElseThrow();
        var context = new LoggerContext();
        try {
            var layout = new PatternLayout();
            layout.setContext(context);
            layout.setPattern(pattern + "%msg");
            layout.start();
            var event = new LoggingEvent(getClass().getName(), context.getLogger("probe"), Level.INFO,
                    "Probe event", null, null);
            event.setMDCPropertyMap(mdc);
            assertEquals(expected, layout.doLayout(event));
        } finally {
            context.stop();
        }
    }

    /** Cả hai trống mới ẩn toàn bộ cụm; không che ngữ cảnh còn lại khi chỉ thiếu một khóa. */
    private static Stream<org.junit.jupiter.params.provider.Arguments> contexts() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(Map.of(), "Probe event"),
                org.junit.jupiter.params.provider.Arguments.of(Map.of("requestId", "probe-123", "api", "GET /probe"),
                        "[requestId=probe-123] [api=GET /probe] Probe event"),
                org.junit.jupiter.params.provider.Arguments.of(Map.of("requestId", "probe-123"),
                        "[requestId=probe-123] [api=] Probe event"),
                org.junit.jupiter.params.provider.Arguments.of(Map.of("api", "ProbeJob.run"),
                        "[requestId=] [api=ProbeJob.run] Probe event"));
    }
}
