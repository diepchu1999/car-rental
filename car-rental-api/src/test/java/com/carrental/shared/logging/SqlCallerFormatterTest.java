package com.carrental.shared.logging;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm định dạng caller độc lập; test luồng HTTP và PostgreSQL thật kiểm wiring ở lớp integration. */
class SqlCallerFormatterTest {
    /** Caller giữ module, thứ tự adapter-service-controller và vị trí source chính xác. */
    @Test
    void preservesModulePathsAndInsideOutOrder() {
        String caller = SqlCallerFormatter.formatCaller(Stream.of(
                frame("vehicle.adapter.out.persistence.VehicleReadAdapter", "list", "VehicleReadAdapter.java", 30),
                frame("vehicle.application.service.VehicleSearchQueryService", "list", "VehicleSearchQueryService.java", 41),
                frame("search.application.service.VehicleSearchQueryService", "search", "VehicleSearchQueryService.java", 62),
                frame("search.adapter.in.rest.PublicVehicleController", "search", "PublicVehicleController.java", 53)));
        assertEquals("vehicle.adapter.out.persistence.VehicleReadAdapter.list(VehicleReadAdapter.java:30)"
                + " ← vehicle.application.service.VehicleSearchQueryService.list(VehicleSearchQueryService.java:41)"
                + " ← search.application.service.VehicleSearchQueryService.search(VehicleSearchQueryService.java:62)"
                + " ← search.adapter.in.rest.PublicVehicleController.search(PublicVehicleController.java:53)", caller);
    }

    /** Proxy, framework và chính tầng logging không chiếm chỗ trong giới hạn frame. */
    @Test
    void filtersInfrastructureAndGeneratedProxyFrames() {
        String caller = SqlCallerFormatter.formatCaller(Stream.of(
                new StackTraceElement("org.springframework.jdbc.JdbcTemplate", "query", "JdbcTemplate.java", 1),
                new StackTraceElement("com.carrentalfake.Probe", "run", "Probe.java", 1),
                frame("shared.logging.SqlCallerFormatter", "formatMessage", "SqlCallerFormatter.java", 1),
                frame("shared.config.SchedulerLoggingConfiguration", "log", "SchedulerLoggingConfiguration.java", 1),
                frame("vehicle.Service$$SpringCGLIB$$0", "run", "Service.java", 1),
                frame("vehicle.$Proxy42", "run", "Proxy.java", 1),
                frame("vehicle.Service", "CGLIB$run$0", "Service.java", 1),
                frame("vehicle.Service", "run", "Service.java", 42)));
        assertEquals("vehicle.Service.run(Service.java:42)", caller);
    }

    /** Hơn 12 frame hợp lệ mới có ký hiệu cắt; frame ngoài ứng dụng không được tính vào hạn mức. */
    @Test
    void capsAtTwelveAcceptedFramesAndMarksTruncation() {
        var ignored = IntStream.range(0, 20).mapToObj(index ->
                new StackTraceElement("org.example.Driver", "run", "Driver.java", index));
        var application = IntStream.rangeClosed(1, 20).mapToObj(index ->
                frame("vehicle.Service", "step" + index, "Service.java", index));
        String caller = SqlCallerFormatter.formatCaller(Stream.concat(ignored, application));
        assertEquals(12, caller.split("Service.java:", -1).length - 1);
        assertTrue(caller.startsWith("vehicle.Service.step1(Service.java:1)"));
        assertTrue(caller.endsWith("vehicle.Service.step12(Service.java:12) ← [truncated]"));
        assertFalse(caller.contains("step13"));
    }

    /** Đúng 12 frame không bị báo cắt giả; giữ frame lặp để không che đệ quy trong code. */
    @Test
    void keepsExactlyTwelveFramesWithoutTruncationMarker() {
        String caller = SqlCallerFormatter.formatCaller(IntStream.range(0, 12)
                .mapToObj(index -> frame("vehicle.Service", "run", "Service.java", 42)));
        assertEquals(12, caller.split("Service.java:", -1).length - 1);
        assertFalse(caller.contains("[truncated]"));
    }

    /** SQL từ Flyway/framework không có frame ứng dụng thì dùng dấu gạch, không bịa nguồn gọi. */
    @Test
    void usesDashWhenNoApplicationFrameExists() {
        assertEquals("-", SqlCallerFormatter.formatCaller(Stream.empty()));
        assertEquals("-", SqlCallerFormatter.formatCaller(Stream.of(
                new StackTraceElement("org.flywaydb.Probe", "run", "Probe.java", 1))));
    }

    /** Bytecode thiếu debug/native được biểu diễn rõ mà không làm hỏng câu SQL đang log. */
    @Test
    void handlesMissingSourceMetadata() {
        assertEquals("vehicle.Service.run(Unknown Source) ← vehicle.Service.run(Service.java)"
                + " ← vehicle.Service.run(Native Method)", SqlCallerFormatter.formatCaller(Stream.of(
                frame("vehicle.Service", "run", null, -1),
                frame("vehicle.Service", "run", "Service.java", -1),
                frame("vehicle.Service", "run", "Service.java", -2))));
    }

    /** SQL đã thay tham số và xuống dòng giữ nguyên; không lộ URL JDBC hoặc chuỗi prepared riêng. */
    @Test
    void preservesExpandedMultilineSqlAndElapsedTime() {
        String sql = "-- Fake SQL probe\nSELECT 'O''Brien', NULL, 7;\n";
        String message = new SqlCallerFormatter().formatMessage(99, "ignored-now", 17, "statement",
                "prepared-sentinel", sql, "jdbc-url-sentinel");
        assertEquals("SQL (17 ms):\ncaller: -\n" + sql, message);
        assertFalse(message.contains("prepared-sentinel"));
        assertFalse(message.contains("jdbc-url-sentinel"));
    }

    /** Không phát nội dung caller cho sự kiện không có SQL. */
    @Test
    void ignoresEmptySql() {
        var formatter = new SqlCallerFormatter();
        for (String sql : new String[] {null, "", " \n\t"}) {
            assertEquals("", formatter.formatMessage(1, "", 0, "statement", "", sql, ""));
        }
    }

    /** Tạo metadata giả chỉ để kiểm định dạng, không giả kết quả stack của request integration. */
    private static StackTraceElement frame(String name, String method, String file, int line) {
        return new StackTraceElement("com.carrental." + name, method, file, line);
    }
}
