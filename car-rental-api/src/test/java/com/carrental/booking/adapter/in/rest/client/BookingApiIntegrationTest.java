package com.carrental.booking.adapter.in.rest.client;

import com.carrental.PostgresTestConfiguration;
import com.carrental.booking.application.port.out.ReadBookingPort;
import com.carrental.identity.adapter.out.persistence.BookingEligibilityTestFixture;
import com.carrental.identity.config.HttpIdentityTestConfiguration;
import com.carrental.identity.config.HttpIdentityTestConfiguration.TestJwtIssuer;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * HTTP/JWT thật → pricing/identity/rental/vehicle → booking/availability → PostgreSQL thật.
 * Chỉ đồng hồ được thay; metadata giấy tờ dùng fixture giả đã xác minh, không mock cổng điều kiện thuê.
 * Không có transaction bao ngoài test, mọi assertion đọc sau khi request đã commit/rollback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT37M")
@Import({PostgresTestConfiguration.class, HttpIdentityTestConfiguration.class,
        BookingEligibilityTestFixture.class, BookingApiIntegrationTest.ApiConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BookingApiIntegrationTest {
    private static final String PATH = "/api/v1/client/bookings";
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    @LocalServerPort private int port;
    @Autowired @Qualifier("bookingApiHttpClient") private HttpClient client;
    @Autowired private TestJwtIssuer tokens;
    @Autowired private ObjectMapper mapper;
    @Autowired private BookingEligibilityTestFixture documents;
    @Autowired private ReadBookingPort reads;
    @Autowired private ReadVehiclePort vehicles;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @MockitoBean(name = "applicationClock") private Clock clock;

    /** Mọi module dùng cùng mốc, để hạn báo giá/giữ chỗ và JWT không lệch nhau. */
    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** BR-122/201: giá từ báo giá, cọc trên tổng gồm bảo hiểm; body giả không đổi chủ/giá/hạn. */
    @ParameterizedTest @ValueSource(strings = {"DAILY", "HOURLY"})
    void createsReadsAndReplaysFrozenBooking(String rentalType) throws Exception {
        var owner = customer(true, "B", "2025-01-01");
        var quote = quote(owner, vehicle(5), rentalType);
        String key = UUID.randomUUID().toString();
        var body = new LinkedHashMap<String, Object>();
        body.put("quoteCode", quote.path("code").asString());
        body.put("customerAccountCode", "TK-FAKE01"); body.put("totalAmount", 1);
        body.put("deposit", 0); body.put("holdExpiresAt", "2099-01-01T00:00:00Z");
        var createdResponse = send("POST", PATH, body, owner.token(), key);
        var created = bookingSuccess(createdResponse, 201);
        String code = created.path("code").asString();
        assertEquals(PATH + "/" + code, createdResponse.headers().firstValue("Location").orElseThrow());
        assertEquals(quote.path("code"), created.path("quoteCode"));
        for (String field : List.of("vehicleCode", "startAt", "endAt", "rentalType", "driveMode", "pickupMethod",
                "lines", "rentalAmount", "insuranceFee", "totalAmount")) assertEquals(quote.path(field), created.path(field), field);
        long total = quote.path("totalAmount").longValue();
        long deposit = BigDecimal.valueOf(total).multiply(new BigDecimal("0.30"))
                .setScale(-3, RoundingMode.HALF_UP).longValueExact();
        assertEquals(deposit, created.path("deposit").longValue());
        assertEquals(total - deposit, created.path("balance").longValue());
        assertEquals(0, created.path("depositRatio").decimalValue().compareTo(new BigDecimal("0.30")));
        assertEquals("PENDING_PAYMENT", created.path("status").asString());
        assertEquals(NOW.plusSeconds(2220), Instant.parse(created.path("holdExpiresAt").asString()));
        assertEquals(created, bookingSuccess(send("GET", PATH + "/" + code, null, owner.token(), null), 200));
        var replay = send("POST", PATH, Map.of("quoteCode", quote.path("code").asString()), owner.token(), key);
        assertEquals(created, bookingSuccess(replay, 201));
        assertEquals(createdResponse.headers().firstValue("Location"), replay.headers().firstValue("Location"));
        var stored = reads.loadOwned(code, owner.code()).orElseThrow();
        assertEquals(created.path("branchCode").asString(), stored.branchCode());
        assertEquals(owner.code(), stored.customerAccountCode());
        assertCounts(owner, stored.vehicleCode(), 1, 1);
    }

    /** security-guideline §2: mã có thật nhưng sai chủ không lộ snapshot hoặc sự tồn tại. */
    @Test
    void hidesBookingAndQuoteFromAnotherCustomer() throws Exception {
        var owner = customer(true, "B", "2025-01-01");
        var other = customer(false, "B", "2025-01-01");
        var quote = quote(owner, vehicle(5), "DAILY");
        var created = bookingSuccess(create(owner, quote, UUID.randomUUID().toString()), 201);
        var hidden = send("GET", PATH + "/" + created.path("code").asString(), null, other.token(), null);
        var missing = send("GET", PATH + "/DT-2611-MISS99", null, other.token(), null);
        error(hidden, 404, "BOOKING_NOT_FOUND"); error(missing, 404, "BOOKING_NOT_FOUND");
        assertEquals(mapper.readTree(missing.body()), mapper.readTree(hidden.body()));
        error(create(other, quote, UUID.randomUUID().toString()), 404, "QUOTE_NOT_FOUND");
        assertCounts(other, quote.path("vehicleCode").asString(), 0, 1);
    }

    /** api-guideline §5 và BR-131: phân biệt key khác nội dung với đơn chờ thứ hai của cùng khách. */
    @Test
    void distinguishesReusedKeyAndSecondPendingBooking() throws Exception {
        var owner = customer(true, "B", "2025-01-01");
        var first = quote(owner, vehicle(5), "DAILY");
        var second = quote(owner, vehicle(5), "DAILY");
        String key = UUID.randomUUID().toString();
        bookingSuccess(create(owner, first, key), 201);
        error(create(owner, second, key), 409, "IDEMPOTENCY_KEY_REUSED");
        error(create(owner, second, UUID.randomUUID().toString()), 409, "BOOKING_PENDING_PAYMENT_EXISTS");
        assertCounts(owner, second.path("vehicleCode").asString(), 1, 0);
    }

    /** BR-104: báo giá không giữ xe; khách thứ hai gặp hold thật phải nhận 409 và không để key/đơn mồ côi. */
    @Test
    void returnsVehicleNotAvailableForOccupiedVehicle() throws Exception {
        var first = customer(true, "B", "2025-01-01");
        var second = customer(true, "B", "2025-01-01");
        String vehicle = vehicle(5);
        var firstQuote = quote(first, vehicle, "DAILY");
        var secondQuote = quote(second, vehicle, "DAILY");
        bookingSuccess(create(first, firstQuote, UUID.randomUUID().toString()), 201);
        error(create(second, secondQuote, UUID.randomUUID().toString()), 409, "VEHICLE_NOT_AVAILABLE");
        assertCounts(second, vehicle, 0, 1);
    }

    /** BR-106/128: HTTP gọi cổng giấy tờ thật, không chỉ kiểm có token CUSTOMER. */
    @ParameterizedTest
    @CsvSource({"false,B,2025-01-01,5,403,IDENTITY_CARD_NOT_VERIFIED",
            "true,B1,2025-01-01,5,422,DRIVER_LICENSE_CLASS_NOT_SUPPORTED",
            "true,B,2025-01-01,16,422,DRIVER_LICENSE_NOT_SUITABLE_FOR_VEHICLE"})
    void rejectsIneligibleDocuments(boolean verified, String licenseClass, String issuedOn, int seats,
            int status, String errorCode) throws Exception {
        var owner = customer(verified, licenseClass, issuedOn);
        String vehicle = vehicle(seats);
        var quote = quote(owner, vehicle, "DAILY");
        error(create(owner, quote, UUID.randomUUID().toString()), status, errorCode);
        assertCounts(owner, vehicle, 0, 0);
    }

    /** BR-128: B1 cũ còn được thuê ô tô; API booking phải dùng ngày cấp của GPLX active. */
    @Test
    void acceptsLegacyB1ForCar() throws Exception {
        var owner = customer(true, "B1", "2024-12-31");
        bookingSuccess(create(owner, quote(owner, vehicle(5), "DAILY"), UUID.randomUUID().toString()), 201);
    }

    /** BR-122: đúng biên 15 phút từ chối báo giá mới, nhưng phát lại và GET đơn cũ vẫn hợp lệ. */
    @Test
    void expiredQuoteDoesNotPreventReplayOrRead() throws Exception {
        String token = tokens.customerToken(clock);
        String code = success(send("GET", "/api/v1/client/accounts/me", null, token, null), 200).path("code").asString();
        documents.verified(code, "B", "2025-01-01");
        var owner = new Customer(token, code);
        var quote = quote(owner, vehicle(5), "DAILY");
        String key = UUID.randomUUID().toString();
        var created = bookingSuccess(create(owner, quote, key), 201);
        when(clock.instant()).thenReturn(NOW.plusSeconds(900));
        var refreshed = new Customer(tokens.customerToken(clock), code);
        error(create(refreshed, quote, UUID.randomUUID().toString()), 422, "QUOTE_EXPIRED");
        assertEquals(created, bookingSuccess(create(refreshed, quote, key), 201));
        assertEquals(created, bookingSuccess(send("GET", PATH + "/" + created.path("code").asString(),
                null, refreshed.token(), null), 200));
    }

    /** Hai request HTTP cùng key trả cùng tài nguyên sau commit, không tạo hai hold. */
    @Test
    void concurrentHttpReplayCreatesOneBooking() throws Exception {
        var owner = customer(true, "B", "2025-01-01");
        var quote = quote(owner, vehicle(5), "DAILY");
        String key = UUID.randomUUID().toString();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> action = () -> { barrier.await(10, TimeUnit.SECONDS); return create(owner, quote, key); };
            var first = pool.submit(action);
            var second = pool.submit(action);
            assertEquals(bookingSuccess(first.get(20, TimeUnit.SECONDS), 201), bookingSuccess(second.get(20, TimeUnit.SECONDS), 201));
        }
        assertCounts(owner, quote.path("vehicleCode").asString(), 1, 1);
    }

    /** Cả POST và GET cần CUSTOMER; token ADMIN thuần không thay được chủ. */
    @ParameterizedTest @CsvSource({"GET,missing,401,UNAUTHENTICATED", "POST,missing,401,UNAUTHENTICATED",
            "GET,admin,403,ACCESS_DENIED", "POST,admin,403,ACCESS_DENIED"})
    void requiresCustomer(String method, String identity, int status, String code) throws Exception {
        error(send(method, method.equals("GET") ? PATH + "/missing" : PATH,
                method.equals("POST") ? Map.of("quoteCode", "BG-2611-TEST01") : null,
                identity.equals("admin") ? tokens.adminToken(clock) : null, UUID.randomUUID().toString()), status, code);
    }

    /** Header bắt buộc và payload thiếu/sai đều 400 trước khi ghi dữ liệu. */
    @Test
    void rejectsMissingKeyAndInvalidPayload() throws Exception {
        var owner = customer(false, "B", "2025-01-01");
        error(send("POST", PATH, Map.of("quoteCode", "BG-2611-TEST01"), owner.token(), null), 400, "INVALID_REQUEST");
        error(send("POST", PATH, Map.of("quoteCode", "BG-2611-TEST01"), owner.token(), " "), 400, "INVALID_REQUEST");
        for (Object body : List.of(Map.of(), Map.of("quoteCode", ""), Map.of("quoteCode", "bad-code"))) {
            error(send("POST", PATH, body, owner.token(), "input-test"), 400, "INVALID_REQUEST");
        }
        error(send("POST", PATH, null, owner.token(), "input-test"), 400, "INVALID_REQUEST");
    }

    /** Tạo tài khoản bằng HTTP rồi gắn metadata giả, không giả CallerDirectory hay bỏ consent. */
    private Customer customer(boolean verified, String licenseClass, String issuedOn) throws Exception {
        String token = tokens.newCustomerToken(clock);
        String code = success(send("GET", "/api/v1/client/accounts/me", null, token, null), 200).path("code").asString();
        if (verified) documents.verified(code, licenseClass, issuedOn);
        return new Customer(token, code);
    }

    /** Xe thật qua vòng đời DRAFT → PENDING_APPROVAL → ACTIVE, giá và giấy tờ hợp lệ. */
    private String vehicle(int seats) throws Exception {
        String admin = tokens.adminToken(clock);
        String branch = success(send("POST", "/api/v1/admin/branches", Map.of("latitude", 10.76, "longitude", 106.66,
                "name", "Booking HTTP fixture", "address", "Local test address"), admin, null), 201).path("code").asString();
        String code = success(send("POST", "/api/v1/admin/vehicles", Map.of("plateNumber", "T09-" + UUID.randomUUID(),
                "branchCode", branch, "fuelType", "PETROL", "seats", seats, "transmission", "AUTOMATIC",
                "make", "Toyota", "model", "Booking HTTP fixture", "dailyRate", 1_000_000,
                "inspectionExpiresOn", "2030-01-01", "liabilityInsuranceExpiresOn", "2030-01-01"), admin, null), 201)
                .path("code").asString();
        success(send("POST", "/api/v1/admin/vehicles/" + code + "/submit-for-approval", null, admin, null), 200);
        success(send("POST", "/api/v1/admin/vehicles/" + code + "/approve", null, admin, null), 200);
        return code;
    }

    /** Khoảng thuê 10:00–18:00 giờ VN, trước lịch lễ fixture và trong cửa sổ sáu tháng. */
    private JsonNode quote(Customer owner, String vehicle, String rentalType) throws Exception {
        return success(send("POST", "/api/v1/client/quotes", Map.of("vehicleCode", vehicle, "rentalType", rentalType,
                "driveMode", "SELF_DRIVE", "pickupMethod", "BRANCH", "startAt", "2026-11-13T03:00:00Z",
                "endAt", "2026-11-13T11:00:00Z"), owner.token(), null), 201);
    }

    /** Gửi đúng hợp đồng tạo đơn, không tự tính giá hoặc hạn trong client. */
    private HttpResponse<String> create(Customer owner, JsonNode quote, String key) throws Exception {
        return send("POST", PATH, Map.of("quoteCode", quote.path("code").asString()), owner.token(), key);
    }

    /** Gửi JWT và header qua HTTP thật; không in token trong thông báo assertion. */
    private HttpResponse<String> send(String method, String path, Object body, String token, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Mọi response của tài khoản/đơn không được cache và có requestId để tra log. */
    private JsonNode envelope(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().matches("[A-Za-z0-9-]{1,64}"));
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        var root = mapper.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), Set.copyOf(root.propertyNames()));
        return root;
    }

    /** Kiểm envelope thành công trước khi dùng mã làm fixture cho request sau. */
    private JsonNode success(HttpResponse<String> response, int status) {
        var root = envelope(response, status);
        assertTrue(root.path("success").booleanValue()); assertTrue(root.path("error").isNull());
        return root.path("data");
    }

    /** Hợp đồng response khóa danh sách trường để ID số/cấu hình không lọt ra ngoài. */
    private JsonNode bookingSuccess(HttpResponse<String> response, int status) {
        var data = success(response, status);
        assertEquals(Set.of("code", "quoteCode", "vehicleCode", "branchCode", "startAt", "endAt", "rentalType",
                "driveMode", "pickupMethod", "lines", "rentalAmount", "insuranceFee", "totalAmount", "depositRatio",
                "deposit", "balance", "status", "holdExpiresAt", "createdAt", "statusChangedAt"), Set.copyOf(data.propertyNames()));
        assertTrue(data.path("code").asString().matches("DT-[0-9]{4}-[A-Z0-9]{6}"));
        for (var line : data.path("lines")) assertEquals(Set.of("type", "dayType", "startAt", "hours", "dailyRate", "uplift", "amount"),
                Set.copyOf(line.propertyNames()));
        return data;
    }

    /** Lỗi phá hoại phải đúng HTTP/mã lỗi và không lộ chi tiết triển khai. */
    private void error(HttpResponse<String> response, int status, String code) {
        var root = envelope(response, status);
        assertFalse(root.path("success").booleanValue()); assertTrue(root.path("data").isNull());
        assertEquals(code, root.path("error").path("code").asString());
        assertFalse(response.body().contains("com.carrental")); assertFalse(response.body().contains("INSERT INTO"));
    }

    /** Đọc độc lập các schema sau response để phát hiện hold mồ côi hoặc claim commit khi request lỗi. */
    private void assertCounts(Customer owner, String vehicle, long bookings, long holds) {
        long vehicleId = vehicles.findByCode(vehicle).orElseThrow().id();
        var counts = jdbc.queryForMap(sql.load("sql/booking/read_creation_counts_for_use_case_test.sql"),
                Map.of("owner", owner.code(), "vehicleId", vehicleId));
        for (String field : List.of("headers", "history", "keys")) assertEquals(bookings, counts.get(field), field);
        assertEquals(holds, counts.get("reservations")); assertEquals(holds, counts.get("held"));
    }

    /** Danh tính fixture chỉ giữ trong test, không xuất token qua toString. */
    private record Customer(String token, String code) {
        /** Không lộ token nếu framework in fixture khi assertion lỗi. */
        @Override public String toString() { return "Customer[code=" + code + "]"; }
    }

    /** Đóng client/selector khi context test kết thúc. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ApiConfiguration {
        /** Dùng chung client an toàn cho hai request đồng thời. */
        @Bean(destroyMethod = "close") HttpClient bookingApiHttpClient() { return HttpClient.newHttpClient(); }
    }
}
