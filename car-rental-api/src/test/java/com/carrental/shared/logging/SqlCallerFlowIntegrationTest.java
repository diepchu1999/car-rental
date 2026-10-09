package com.carrental.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.search.SearchIntegrationTestConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-guideline §3: HTTP/job thật → use case → adapter → PostgreSQL Testcontainers, không mock SQL.
 * Không tự bật sql-log: chạy cùng test ở hai mode để chứng minh mặc định không có SQL/caller.
 * Nhịp job ngắn chỉ trong context này; dữ liệu/múi giờ dùng fixture tìm kiếm BR-125 đã có.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-sweep-interval=PT0.25S")
@Import({PostgresTestConfiguration.class, SearchIntegrationTestConfiguration.class,
        SqlCallerFlowIntegrationTest.GateConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SqlCallerFlowIntegrationTest {
    private static final String SEARCH_PATH = "/api/v1/public/vehicles";
    private static final String GATE_LOGGER = "com.carrental.shared.logging.concurrentProbe";
    @LocalServerPort private int port;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper mapper;
    @Autowired private SearchIntegrationTestConfiguration.Fixtures fixtures;
    @Autowired private RequestGate gate;
    @Autowired private WriteReservationPort writes;
    @Autowired private ReadReservationPort reads;
    @Autowired private PlatformTransactionManager transactions;

    /** BR-003/808: SELECT từ HTTP thật có đầy đủ adapter-service-controller và số dòng, không lộ query. */
    @Test
    void tracesRealBranchRequestWithoutQueryInApiContext() throws Exception {
        var branch = fixtures.branch(10.76, 106.66);
        String path = "/api/v1/admin/branches/" + branch.code();
        String id = "branch-" + UUID.randomUUID();
        try (var logs = new LogEventCapture("p6spy")) {
            JsonNode data = successful(get(path + "?probe=query-sentinel", id), id);
            assertEquals(branch.code(), data.path("code").asString());
            var events = requestSql(logs, id);
            if (!sqlLogEnabled()) {
                assertTrue(logs.events().isEmpty(), "Without sql-log no request or job SQL may be logged.");
                return;
            }
            assertEquals(1, events.size());
            var event = events.getFirst();
            assertContext(event, id, "GET " + path);
            assertTrue(event.getFormattedMessage().contains("FROM branch.branch AS b"));
            assertFalse(event.getFormattedMessage().contains("query-sentinel"));
            assertCallerOrder(event,
                    "branch.adapter.out.persistence.BranchReadAdapter.findByCode",
                    "branch.application.service.BranchQueryService.get",
                    "branch.adapter.in.rest.admin.AdminBranchController.get");
        }
    }

    /** HTTP thật vẫn tra đủ mã và trả lỗi cũ; chỉ nhãn api của SQL bị cắt, không thay tham số nghiệp vụ. */
    @Test
    void boundsLongApiContextWithoutTruncatingDatabaseInput() throws Exception {
        String code = "LOG-BOUND-" + "a".repeat(1024) + "-tail";
        String path = "/api/v1/admin/branches/" + code;
        String id = "bounded-" + UUID.randomUUID();
        String expectedApi = ("GET " + path).substring(0, 501) + "[truncated]";
        try (var logs = new LogEventCapture("p6spy")) {
            var response = get(path + "?probe=not-in-api-context", id);
            assertEquals(404, response.statusCode(), response.body());
            assertEquals(List.of(id), response.headers().allValues("X-Request-Id"));
            JsonNode body = mapper.readTree(response.body());
            assertEquals(Set.of("success", "data", "error"), Set.copyOf(body.propertyNames()));
            assertFalse(body.path("success").booleanValue());
            assertTrue(body.path("data").isNull());
            assertEquals(Set.of("code", "message"), Set.copyOf(body.path("error").propertyNames()));
            assertEquals("BRANCH_NOT_FOUND", body.path("error").path("code").asString());
            assertFalse(response.body().contains("[truncated]"));
            assertFalse(response.body().contains("caller:"));
            assertFalse(response.body().contains(code));
            if (!sqlLogEnabled()) {
                assertTrue(logs.events().isEmpty());
                return;
            }
            var events = requestSql(logs, id);
            assertEquals(1, events.size());
            assertEquals(512, expectedApi.length());
            assertContext(events.getFirst(), id, expectedApi);
            assertTrue(events.getFirst().getFormattedMessage().contains(code),
                    "The database must receive the full input, not the truncated log label.");
            assertFalse(events.getFirst().getFormattedMessage().contains("not-in-api-context"));
        }
    }

    /** BR-125: tìm được xe thật và cùng một SQL chứa hai service trùng tên nhưng khác đường dẫn module. */
    @Test
    void distinguishesBothVehicleSearchServicesOnRealSearchRoute() throws Exception {
        String model = "CallerModel-" + UUID.randomUUID();
        var branch = fixtures.branch(10.76, 106.66);
        var vehicle = fixtures.active(branch.code(), model);
        String id = "search-" + UUID.randomUUID();
        try (var logs = new LogEventCapture("p6spy")) {
            JsonNode data = successful(get(searchPath(model), id), id);
            assertEquals(1, data.path("items").size());
            assertEquals(vehicle.code(), data.path("items").get(0).path("code").asString());
            if (!sqlLogEnabled()) {
                assertTrue(logs.events().isEmpty(), "The real search flow must bypass SQL logging by default.");
                return;
            }
            var events = requestSql(logs, id);
            assertFalse(events.isEmpty());
            events.forEach(event -> assertContext(event, id, "GET " + SEARCH_PATH));
            var candidates = events.stream().filter(event -> event.getFormattedMessage()
                    .contains("FROM vehicle.vehicle")).toList();
            assertEquals(1, candidates.size(), "The candidate query must really execute once.");
            assertCallerOrder(candidates.getFirst(),
                    "vehicle.adapter.out.persistence.VehicleReadAdapter.findSearchCandidates",
                    "vehicle.application.service.VehicleSearchQueryService.list",
                    "vehicle.adapter.in.internal.VehicleSearchDirectoryAdapter.list",
                    "search.application.service.VehicleSearchQueryService.search",
                    "search.adapter.in.rest.publicapi.PublicVehicleSearchController.search");
        }
    }

    /** Hai HTTP worker cùng hoạt động trước/sau SQL, API và ID của search không lẫn với request đọc chi nhánh. */
    @Test
    void concurrentHttpRequestsKeepSeparateApiAndRequestIds() throws Exception {
        String model = "ConcurrentCaller-" + UUID.randomUUID();
        var branch = fixtures.branch(10.76, 106.66);
        var vehicle = fixtures.active(branch.code(), model);
        String branchPath = "/api/v1/admin/branches/" + branch.code();
        String branchId = "parallel-branch-" + UUID.randomUUID();
        String searchId = "parallel-search-" + UUID.randomUUID();
        Map<String, String> expected = Map.of(branchId, "GET " + branchPath, searchId, "GET " + SEARCH_PATH);
        gate.arm(expected.keySet());
        try (var sql = new LogEventCapture("p6spy"); var probes = new LogEventCapture(GATE_LOGGER);
             var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> get(branchPath, branchId));
            var second = workers.submit(() -> get(searchPath(model), searchId));
            assertEquals(branch.code(), successful(first.get(20, TimeUnit.SECONDS), branchId).path("code").asString());
            assertEquals(vehicle.code(), successful(second.get(20, TimeUnit.SECONDS), searchId)
                    .path("items").get(0).path("code").asString());
            // Bốn log nằm trong hai lượt filter đang cùng hoạt động; không suy đồng thời từ sendAsync.
            var events = probes.events();
            assertEquals(4, events.size());
            expected.forEach((id, api) -> {
                var own = events.stream().filter(event -> id.equals(event.getMDCPropertyMap().get("requestId"))).toList();
                assertEquals(2, own.size());
                own.forEach(event -> assertContext(event, id, api));
                assertEquals(Set.of("Concurrent request entered", "Concurrent request finished"),
                        own.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.toSet()));
                if (sqlLogEnabled()) {
                    var queries = requestSql(sql, id);
                    assertFalse(queries.isEmpty());
                    queries.forEach(event -> assertContext(event, id, api));
                }
            });
            if (!sqlLogEnabled()) {
                assertTrue(sql.events().isEmpty());
            }
        } finally {
            gate.disarm();
        }
    }

    /** Đầu vào thiếu vẫn bị chặn trước SQL; lỗi 400 giữ ID nhưng không lộ caller/class hoặc thay JSON. */
    @Test
    void rejectsInvalidSearchWithoutSqlOrInternalDetails() throws Exception {
        String id = "invalid-search-" + UUID.randomUUID();
        try (var logs = new LogEventCapture("p6spy")) {
            var response = get(SEARCH_PATH, id);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals(List.of(id), response.headers().allValues("X-Request-Id"));
            JsonNode body = mapper.readTree(response.body());
            assertEquals(Set.of("success", "data", "error"), Set.copyOf(body.propertyNames()));
            assertFalse(body.path("success").booleanValue());
            assertTrue(body.path("data").isNull());
            assertEquals(Set.of("code", "message"), Set.copyOf(body.path("error").propertyNames()));
            assertEquals("INVALID_REQUEST", body.path("error").path("code").asString());
            assertFalse(response.body().contains("caller:"));
            assertFalse(response.body().contains(".java:"));
            assertFalse(response.body().contains("com.carrental"));
            assertTrue(requestSql(logs, id).isEmpty(), "Invalid input must not reach the database.");
        }
    }

    /** BR-103: timer thật nhả HELD đã hết hạn; SQL chứa tên job và mỗi lượt có job-UUID riêng. */
    @Test
    void scheduledSweepUsesRealSqlAndCorrectJobIdentity() {
        var branch = fixtures.branch(10.76, 106.66);
        var vehicle = fixtures.active(branch.code(), "JobCaller-" + UUID.randomUUID());
        Instant now = Instant.parse("2030-01-15T00:00:00Z");
        Reservation original = Reservation.createHeld("KL-LOG001", vehicle.id(),
                ReservationPeriod.finite(now.plusSeconds(86400), now.plusSeconds(93600)),
                "caller-job-booking", Duration.ofHours(1), now.minusSeconds(7200));
        try (var logs = new LogEventCapture("p6spy")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertTrue(writes.insert(original).isPresent());
                assertEquals(ReservationStatus.HELD, reads.loadAggregate(original.code()).orElseThrow().status());
            });
            // Chỉ đọc để đợi timer: không gọi expireHolds/sweep bằng tay và không đổi trạng thái trong test.
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                    assertEquals(ReservationStatus.RELEASED, reads.loadAggregate(original.code()).orElseThrow().status()));
            if (!sqlLogEnabled()) {
                assertTrue(logs.events().isEmpty(), "A real scheduled UPDATE must also bypass SQL logging by default.");
                return;
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> jobSql(logs).size() >= 2);
            var events = jobSql(logs);
            var ids = new java.util.HashSet<String>();
            for (var event : events) {
                String id = event.getMDCPropertyMap().get("requestId");
                assertNotNull(id);
                assertTrue(id.startsWith("job-"));
                assertEquals(id.substring(4), UUID.fromString(id.substring(4)).toString());
                assertTrue(ids.add(id), "Each scheduled SQL execution must belong to a fresh job invocation.");
                assertContext(event, id, "ReservationHoldExpiryScheduler.sweep");
                assertCallerOrder(event,
                        "availability.adapter.out.persistence.ReservationWriteAdapter.releaseExpiredHolds",
                        "availability.application.service.ReservationCommandService.expireHolds",
                        "availability.adapter.in.scheduler.ReservationHoldExpiryScheduler.sweep");
            }
        }
    }

    /** Chọn log UPDATE thật từ job; không trộn INSERT fixture hoặc SELECT kiểm trạng thái ở thread test. */
    private static List<ILoggingEvent> jobSql(LogEventCapture logs) {
        return logs.events().stream().filter(event -> event.getFormattedMessage()
                .contains("UPDATE availability.reservation")).toList();
    }

    /** Chọn sự kiện bằng MDC chụp tại thời điểm phát, không dựa vào thứ tự của các thread. */
    private static List<ILoggingEvent> requestSql(LogEventCapture logs, String id) {
        return logs.events().stream().filter(event -> id.equals(event.getMDCPropertyMap().get("requestId"))).toList();
    }

    /** Kiểm API chỉ có method/path hoặc tên job; không đưa query/header vào nhãn. */
    private static void assertContext(ILoggingEvent event, String id, String api) {
        assertEquals(id, event.getMDCPropertyMap().get("requestId"));
        assertEquals(api, event.getMDCPropertyMap().get("api"));
    }

    /** Kiểm thứ tự và source line thực tế, không ghim một con số sẽ đổi khi code được chỉnh sửa. */
    private static void assertCallerOrder(ILoggingEvent event, String... methods) {
        String caller = event.getFormattedMessage().lines().filter(line -> line.startsWith("caller: "))
                .findFirst().orElseThrow(() -> new AssertionError("Missing SQL caller: " + event.getFormattedMessage()));
        int previous = -1;
        for (String method : methods) {
            String className = method.substring(0, method.lastIndexOf('.'));
            String file = className.substring(className.lastIndexOf('.') + 1) + ".java";
            var matcher = Pattern.compile(Pattern.quote(method) + "\\(" + Pattern.quote(file) + ":[1-9][0-9]*\\)")
                    .matcher(caller);
            assertTrue(matcher.find(), () -> "Missing real method/source line: " + method + " in " + caller);
            assertTrue(matcher.start() > previous, "Caller must run from adapter out to controller/job.");
            previous = matcher.start();
        }
        assertFalse(caller.contains("com.carrental."));
        assertFalse(caller.contains("$$"));
        assertFalse(caller.contains("$Proxy"));
        assertFalse(caller.contains("shared.logging."));
    }

    /** Kiểm JSON/header thật giữ nguyên hợp đồng, không đưa thông tin logging ra response. */
    private JsonNode successful(HttpResponse<String> response, String id) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of(id), response.headers().allValues("X-Request-Id"));
        JsonNode body = mapper.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), Set.copyOf(body.propertyNames()));
        assertTrue(body.path("success").booleanValue());
        assertTrue(body.path("error").isNull());
        assertFalse(response.body().contains("caller:"));
        assertFalse(response.body().contains(".java:"));
        return body.path("data");
    }

    /** Gửi HTTP tới cổng ngẫu nhiên, có timeout; không gọi controller hoặc use case thay cho HTTP. */
    private HttpResponse<String> get(String path, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("X-Request-Id", id)
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1").GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Khoảng tìm kiếm hợp lệ theo đồng hồ fixture; model dùng UUID nên không cần ký tự cần encode. */
    private static String searchPath(String model) {
        return SEARCH_PATH + "?latitude=10.76&longitude=106.66&startInclusive=2030-01-15T03:00:00Z"
                + "&endExclusive=2030-01-15T09:00:00Z&rentalType=DAILY&driveMode=SELF_DRIVE&model=" + model;
    }

    /** Đọc mode thật, không bật profile bằng annotation và không giả P6Spy khi đang kiểm mặc định. */
    private boolean sqlLogEnabled() {
        return environment.acceptsProfiles(Profiles.of("sql-log"));
    }

    /** Chỉ bổ sung điểm đồng bộ HTTP trong context test, không tạo endpoint hoặc thay use case. */
    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfiguration {
        /** Đối tượng quản lý một cuộc đua được test bật/tắt tường minh. */
        @Bean
        RequestGate requestGate() {
            return new RequestGate();
        }

        /** Chạy sau RequestIdFilter để kiểm context thật đã được gắn trước khi vào controller. */
        @Bean
        FilterRegistrationBean<RequestGate> requestGateRegistration(RequestGate gate) {
            var registration = new FilterRegistrationBean<>(gate);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }

    /** Chặn hai request được chọn tại hai mốc, không đọc/log body, query hoặc header tùy ý. */
    static class RequestGate extends OncePerRequestFilter {
        private final AtomicReference<Round> round = new AtomicReference<>();

        /** Khởi tạo hai barrier độc lập, không tái sử dụng cuộc đua của test trước. */
        void arm(Set<String> ids) {
            assertTrue(round.compareAndSet(null, new Round(Set.copyOf(ids), new CyclicBarrier(2), new CyclicBarrier(2))));
        }

        /** Không giữ ID request sau khi phép thử kết thúc. */
        void disarm() {
            round.set(null);
        }

        /** Hai thread phải đến trước khi chạy tiếp; phát log trước/sau để kiểm cách ly MDC ở cả hai mốc. */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Round current = round.get();
            if (current == null || !current.ids().contains(MDC.get("requestId"))) {
                chain.doFilter(request, response);
                return;
            }
            rendezvous(current.entered());
            LoggerFactory.getLogger(GATE_LOGGER).info("Concurrent request entered");
            chain.doFilter(request, response);
            rendezvous(current.finished());
            LoggerFactory.getLogger(GATE_LOGGER).info("Concurrent request finished");
        }

        /** Timeout làm test đỏ rõ ràng thay vì treo; giữ cờ interrupt nếu thread bị hủy. */
        private static void rendezvous(CyclicBarrier barrier) throws ServletException {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ServletException("Interrupted while coordinating HTTP requests.", failure);
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure) {
                throw new ServletException("Both HTTP requests must overlap during the test.", failure);
            }
        }
    }

    /** Trạng thái test đồng thời, không phải DTO nghiệp vụ hoặc dữ liệu trả qua REST. */
    private record Round(Set<String> ids, CyclicBarrier entered, CyclicBarrier finished) {
    }
}
