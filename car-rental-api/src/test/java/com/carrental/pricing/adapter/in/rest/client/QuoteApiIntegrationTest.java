package com.carrental.pricing.adapter.in.rest.client;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static com.carrental.pricing.domain.PricingTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP thật đi qua controller, booking/vehicle API, pricing và PostgreSQL; không dùng transaction bao ngoài test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class,
        QuoteApiIntegrationTest.ApiConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuoteApiIntegrationTest {
    private static final String PATH = "/api/v1/client/quotes";
    @LocalServerPort private int port;
    @Autowired @Qualifier("quoteApiHttpClient") private HttpClient client;
    @Autowired private TestJwtIssuer tokens;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.carrental.vehicle.application.port.out.ReadVehiclePort vehicleReads;
    @Autowired private com.carrental.availability.api.AvailabilityDirectory availability;

    /** BR-120: báo giá cần CUSTOMER; ADMIN thuần không có quyền khách và không tự nhận thêm vai. */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "admin"})
    void quoteRequiresCustomerIdentity(String variant) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + "/missing-quote"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (variant.equals("admin")) {
            request.header("Authorization", "Bearer " + tokens.adminToken(clock));
        }
        error(client.send(request.build(), HttpResponse.BodyHandlers.ofString()),
                variant.equals("admin") ? 403 : 401,
                variant.equals("admin") ? "ACCESS_DENIED" : "UNAUTHENTICATED");
    }

    /** Toàn bộ ví dụ a–k2 phải đúng sau khi qua JSON, validation, JDBC và reload; tiền bảo hiểm là khoản riêng. */
    @ParameterizedTest(name = "HTTP example {0}")
    @CsvSource({
            "a,DAILY,2026-11-13T10:00,2026-11-15T10:00,1000000,2200000,200000,2",
            "b,DAILY,2026-11-10T10:00,2026-11-11T12:00,1000000,1400000,100000,2",
            "c,DAILY,2026-11-10T10:00,2026-11-11T15:00,1000000,2000000,200000,2",
            "d,DAILY,2026-11-10T08:00,2026-11-10T18:00,1000000,1000000,100000,1",
            "e,DAILY,2026-11-10T10:00,2026-11-11T10:01,1000000,1200000,100000,2",
            "f,HOURLY,2026-11-10T08:00,2026-11-10T13:00,1000000,600000,100000,2",
            "g,HOURLY,2026-11-10T08:00,2026-11-10T16:00,1000000,900000,100000,2",
            "h,HOURLY,2026-11-10T08:00,2026-11-10T17:00,1000000,1000000,100000,1",
            "i,HOURLY,2026-11-14T08:00,2026-11-14T13:00,1000000,720000,100000,2",
            "j,DAILY,2026-11-23T10:00,2026-11-25T10:00,1000000,2500000,200000,2",
            "k,DAILY,2027-02-04T10:00,2027-02-07T10:00,855000,3849000,300000,3",
            "k2,HOURLY,2026-11-10T08:00,2026-11-10T15:00,855000,685000,100000,2"
    })
    void approvedExamplesMatchHttpAndStoredQuote(String name, String rentalType, String start, String end,
            long rate, long rentalAmount, long insuranceFee, int lineCount) throws Exception {
        String vehicle = vehicle(rate, true);
        var response = post(PATH, payload(vehicle, rentalType, start, end));
        var data = quoteSuccess(response, 201);
        assertEquals(PATH + "/" + data.path("code").asString(), response.headers().firstValue("Location").orElseThrow());
        assertEquals(vehicle, data.path("vehicleCode").asString());
        assertEquals(rentalAmount, data.path("rentalAmount").longValue());
        assertEquals(insuranceFee, data.path("insuranceFee").longValue());
        assertEquals(rentalAmount + insuranceFee, data.path("totalAmount").longValue());
        assertEquals(lineCount, data.path("lines").size());
        long sum = 0;
        for (var line : data.path("lines")) {
            assertEquals(rate, line.path("dailyRate").longValue());
            sum += line.path("amount").longValue();
        }
        assertEquals(rentalAmount, sum);
        assertEquals(Duration.ofMinutes(15), Duration.between(Instant.parse(data.path("createdAt").asString()),
                Instant.parse(data.path("expiresAt").asString())));
        if (name.equals("k")) {
            for (var line : data.path("lines")) {
                assertEquals("HOLIDAY", line.path("dayType").asString());
                assertEquals(1_283_000, line.path("amount").longValue());
            }
        }
        if (name.equals("k2")) {
            assertEquals(428_000, data.path("lines").get(0).path("amount").longValue());
            assertEquals(257_000, data.path("lines").get(1).path("amount").longValue());
        }
        assertEquals(data, quoteSuccess(send("GET", PATH + "/" + data.path("code").asString(), null, null), 200));
        // BR-126: HTTP search và HTTP quote dùng đúng một cách tính cho toàn bộ ví dụ a–k2.
        String vehicleModel = vehicleReads.findByCode(vehicle).orElseThrow().model();
        String searchPath = "/api/v1/public/vehicles?latitude=10.76&longitude=106.66"
                + "&startInclusive=" + at(start) + "&endExclusive=" + at(end)
                + "&rentalType=" + rentalType + "&driveMode=SELF_DRIVE&sort=PRICE_ASC&model="
                + java.net.URLEncoder.encode(vehicleModel, java.nio.charset.StandardCharsets.UTF_8);
        var searchResult = success(send("GET", searchPath, null, null), 200);
        assertEquals(1, searchResult.path("items").size());
        var found = searchResult.path("items").get(0);
        assertEquals(vehicle, found.path("code").asString());
        assertEquals(data.path("rentalAmount").longValue(), found.path("rentalAmount").longValue());
        assertFalse(found.has("insuranceFee"));
    }

    /** Đổi giá qua PUT không đổi báo giá cũ; tạo lại cùng đầu vào ra tài nguyên và giá mới. */
    @Test
    void oldQuoteSurvivesRateChangeAndRepeatedCreateIsNewResource() throws Exception {
        String vehicle = vehicle(1_000_000, true);
        var payload = payload(vehicle, "DAILY", "2026-11-10T08:00", "2026-11-10T18:00");
        var old = quoteSuccess(post(PATH, payload), 201);
        assertEquals(200, send("PUT", "/api/v1/admin/vehicles/" + vehicle + "/daily-rate",
                "{\"dailyRate\":855000}", "application/json").statusCode());
        assertEquals(old, quoteSuccess(send("GET", PATH + "/" + old.path("code").asString(), null, null), 200));
        var fresh = quoteSuccess(post(PATH, payload), 201);
        assertNotEquals(old.path("code").asString(), fresh.path("code").asString());
        assertEquals(855_000, fresh.path("rentalAmount").longValue());
        var again = quoteSuccess(post(PATH, payload), 201);
        assertNotEquals(fresh.path("code").asString(), again.path("code").asString());
        assertEquals(fresh.path("rentalAmount"), again.path("rentalAmount"));
    }

    /** Xe đã bận vẫn được tính báo giá; báo giá không giữ chỗ hoặc cam kết lịch trống, ADR-0005. */
    @Test
    void quoteDoesNotCheckOrChangeExistingReservation() throws Exception {
        String vehicle = vehicle(1_000_000, true);
        long id = vehicleReads.findByCode(vehicle).orElseThrow().id();
        var period = new com.carrental.availability.api.Period(at("2026-11-10T08:00"), at("2026-11-10T18:00"));
        availability.block(id, period, com.carrental.availability.api.BlockKind.MAINTENANCE, "Quote test fixture");
        assertEquals(Set.of(id), availability.findBusyVehicleIds(period, Duration.ZERO, List.of(id)));
        var result = quoteSuccess(post(PATH, payload(vehicle, "DAILY", "2026-11-10T08:00", "2026-11-10T18:00")), 201);
        assertEquals(1_000_000, result.path("rentalAmount").longValue());
        assertEquals(Set.of(id), availability.findBusyVehicleIds(period, Duration.ZERO, List.of(id)));
    }

    /** Chỉ ACTIVE được báo giá; DRAFT và mã không tồn tại có cùng lỗi, không lộ trạng thái hồ sơ. */
    @Test
    void draftAndMissingVehicleAreNotFound() throws Exception {
        error(post(PATH, payload(vehicle(1_000_000, false), "DAILY", "2026-11-10T08:00", "2026-11-10T18:00")),
                404, "VEHICLE_NOT_FOUND");
        error(post(PATH, payload("missing-vehicle", "DAILY", "2026-11-10T08:00", "2026-11-10T18:00")),
                404, "VEHICLE_NOT_FOUND");
        error(send("GET", PATH + "/missing-quote", null, null), 404, "QUOTE_NOT_FOUND");
    }

    /** Các lựa chọn chưa hỗ trợ trả đúng mã và ưu tiên tất định, ngay cả khi xe không tồn tại. */
    @ParameterizedTest
    @CsvSource({"MONTHLY,WITH_DRIVER,DELIVERY,QUOTE_RENTAL_TYPE_NOT_SUPPORTED",
            "DAILY,WITH_DRIVER,DELIVERY,QUOTE_DRIVE_MODE_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,DELIVERY,QUOTE_PICKUP_METHOD_NOT_SUPPORTED"})
    void rejectsUnsupportedChoicesBeforeVehicleLookup(String rental, String drive, String pickup, String code) throws Exception {
        var body = payload("missing-vehicle", rental, "2026-11-10T08:00", "2026-11-10T18:00");
        body.put("driveMode", drive); body.put("pickupMethod", pickup);
        error(post(PATH, body), 422, code);
    }

    /** Dùng đúng điều kiện booking: tối thiểu, giờ chi nhánh và cửa sổ nhận xe. */
    @ParameterizedTest
    @CsvSource({"HOURLY,2026-11-10T08:00,2026-11-10T11:00,RENTAL_DURATION_TOO_SHORT",
            "DAILY,2026-11-10T23:30,2026-11-11T10:00,OUTSIDE_BRANCH_HOURS",
            "DAILY,2027-05-10T10:00,2027-05-11T10:00,BOOKING_WINDOW_VIOLATION"})
    void rejectsRentalTermsBeforeVehicleLookup(String rental, String start, String end, String code) throws Exception {
        error(post(PATH, payload("missing-vehicle", rental, start, end)), 422, code);
    }

    /** Vượt lịch đã khai báo phải từ chối; MONTHLY vẫn ưu tiên trước lỗi lịch. */
    @Test
    void undeclaredCalendarAndErrorPrecedenceAreExplicit() throws Exception {
        var body = payload("missing-vehicle", "DAILY", "2027-03-01T10:00", "2028-01-02T10:00");
        error(post(PATH, body), 422, "HOLIDAY_CALENDAR_NOT_DECLARED");
        body.put("rentalType", "MONTHLY");
        error(post(PATH, body), 422, "QUOTE_RENTAL_TYPE_NOT_SUPPORTED");
    }

    /** Thiếu và null từng trường đều 400; không tự dùng gói ngày/tự lái/nhận chi nhánh làm mặc định. */
    @ParameterizedTest
    @ValueSource(strings = {"vehicleCode", "startAt", "endAt", "rentalType", "driveMode", "pickupMethod"})
    void rejectsMissingAndNullInput(String field) throws Exception {
        var body = payload("XE-TEST01", "DAILY", "2026-11-10T08:00", "2026-11-10T18:00");
        body.remove(field); error(post(PATH, body), 400, "INVALID_REQUEST");
        body.put(field, null); error(post(PATH, body), 400, "INVALID_REQUEST");
    }

    /** JSON/ngày/enum sai và khoảng bằng nhau đều INVALID_REQUEST, không lộ lỗi framework/SQL. */
    @Test
    void rejectsMalformedInput() throws Exception {
        for (String raw : new String[]{"{", "null", ""}) error(send("POST", PATH, raw, "application/json"), 400, "INVALID_REQUEST");
        var body = payload("XE-TEST01", "DAILY", "2026-11-10T08:00", "2026-11-10T18:00");
        body.put("endAt", body.get("startAt")); error(post(PATH, body), 400, "INVALID_REQUEST");
        body.put("endAt", "bad-date"); error(post(PATH, body), 400, "INVALID_REQUEST");
        body.put("endAt", at("2026-11-10T18:00").toString()); body.put("rentalType", "UNKNOWN");
        error(post(PATH, body), 400, "INVALID_REQUEST");
        error(send("POST", PATH, "plain text", "text/plain"), 415, "INVALID_REQUEST");
    }

    /** Client không quyết định tiền, mã hoặc hạn; các trường ngoài hợp đồng được bỏ qua. */
    @Test
    void ignoresClientSuppliedPriceAndExpiry() throws Exception {
        var body = payload(vehicle(1_000_000, true), "DAILY", "2026-11-10T08:00", "2026-11-10T18:00");
        body.put("code", "BG-0001-FAKE00"); body.put("rentalAmount", 1); body.put("insuranceFee", 0);
        body.put("expiresAt", "2000-01-01T00:00:00Z"); body.put("id", 123);
        var result = quoteSuccess(post(PATH, body), 201);
        assertTrue(result.path("code").asString().startsWith("BG-2610-"));
        assertEquals(1_000_000, result.path("rentalAmount").longValue());
        assertEquals(100_000, result.path("insuranceFee").longValue());
        assertEquals(Duration.ofMinutes(15), Duration.between(Instant.parse(result.path("createdAt").asString()),
                Instant.parse(result.path("expiresAt").asString())));
    }

    /** Độ chính xác không lưu được bị trả 400 trước tra xe, không làm tròn thành khoảng có giá khác. */
    @Test
    void rejectsSubmicrosecondRentalTimestamp() throws Exception {
        var body = payload("missing-vehicle", "HOURLY", "2026-11-10T08:00", "2026-11-10T12:00");
        body.put("startAt", "2026-11-10T01:00:00.000000001Z");
        body.put("endAt", "2026-11-10T05:00:00.000000001Z");
        error(post(PATH, body), 400, "INVALID_REQUEST");
    }

    /** Chuẩn bị dữ liệu bằng API admin thật, giữ nguyên vòng đời DRAFT → PENDING_APPROVAL → ACTIVE. */
    private String vehicle(long rate, boolean approve) throws Exception {
        String branch = success(post("/api/v1/admin/branches", Map.of("latitude", 10.76, "longitude", 106.66,
                "name", "Quote Test Branch", "address", "123 Test Street")), 201).path("code").asString();
        String vehicle = success(post("/api/v1/admin/vehicles", Map.of(
                "plateNumber", "QUOTE-" + UUID.randomUUID(), "fuelType", "PETROL", "branchCode", branch,
                "seats", 5, "transmission", "AUTOMATIC", "make", "Toyota", "model", "Quote-" + UUID.randomUUID(), "dailyRate", rate,
                "inspectionExpiresOn", "2030-01-01", "liabilityInsuranceExpiresOn", "2030-01-01")), 201)
                .path("code").asString();
        if (approve) {
            success(send("POST", "/api/v1/admin/vehicles/" + vehicle + "/submit-for-approval", null, null), 200);
            success(send("POST", "/api/v1/admin/vehicles/" + vehicle + "/approve", null, null), 200);
        }
        return vehicle;
    }

    /** Request chỉ có các giá trị client được chọn, thời gian chuyển từ giờ VN sang Instant. */
    private static Map<String, Object> payload(String vehicle, String rental, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("vehicleCode", vehicle); body.put("startAt", at(start).toString()); body.put("endAt", at(end).toString());
        body.put("rentalType", rental); body.put("driveMode", "SELF_DRIVE"); body.put("pickupMethod", "BRANCH");
        return body;
    }

    /** Gửi JSON qua HTTP thật với cổng do Spring chọn. */
    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        return send("POST", path, mapper.writeValueAsString(body), "application/json");
    }

    /** ADMIN chuẩn bị xe, CUSTOMER gọi báo giá; tìm kiếm public không gửi token, không gộp hai vai. */
    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1");
        if (path.startsWith("/api/v1/admin/")) {
            builder.header("Authorization", "Bearer " + tokens.adminToken(clock));
        } else if (path.startsWith("/api/v1/client/")) {
            builder.header("Authorization", "Bearer " + tokens.customerToken(clock));
        }
        if (contentType != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Đối chiếu envelope thống nhất, không coi HTTP 4xx có chủ đích là lỗi test. */
    private JsonNode envelope(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        var root = mapper.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), Set.copyOf(root.propertyNames()));
        return root;
    }

    /** Lấy dữ liệu thành công cho cả fixture admin và báo giá. */
    private JsonNode success(HttpResponse<String> response, int status) {
        var root = envelope(response, status); assertTrue(root.path("success").booleanValue());
        assertTrue(root.path("error").isNull()); return root.path("data");
    }

    /** Không có ID số, cấu hình hoặc lịch lễ trên response; các dòng là DTO đúng hợp đồng R12. */
    private JsonNode quoteSuccess(HttpResponse<String> response, int status) {
        var data = success(response, status);
        assertEquals(Set.of("code", "vehicleCode", "startAt", "endAt", "rentalType", "driveMode", "pickupMethod",
                "lines", "rentalAmount", "insuranceFee", "totalAmount", "expiresAt", "createdAt"), Set.copyOf(data.propertyNames()));
        assertTrue(data.path("code").asString().matches("BG-[0-9]{4}-[A-Z0-9]{6}"));
        for (var line : data.path("lines")) assertEquals(Set.of("type", "dayType", "startAt", "hours", "dailyRate", "uplift", "amount"),
                Set.copyOf(line.propertyNames()));
        return data;
    }

    /** Kiểm đúng mã lỗi, không trả thông tin class, SQL hoặc stack trace ra HTTP. */
    private void error(HttpResponse<String> response, int status, String code) {
        var root = envelope(response, status); assertFalse(root.path("success").booleanValue());
        assertTrue(root.path("data").isNull()); assertEquals(code, root.path("error").path("code").asString());
        assertFalse(response.body().contains("com.carrental")); assertFalse(response.body().contains("INSERT INTO"));
    }

    /** Context riêng để các ngày nghiệm thu cố định không hết hạn theo đồng hồ máy chạy test. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ApiConfiguration {
        /** Mốc tạo Việt Nam tháng 10/2026, mọi ví dụ nằm trong cửa sổ sáu tháng. */
        @Bean @Primary Clock quoteApiClock() { return Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZONE); }
        /** Đóng client và luồng selector khi context kết thúc. */
        @Bean(destroyMethod = "close") HttpClient quoteApiHttpClient() { return HttpClient.newHttpClient(); }
    }
}
