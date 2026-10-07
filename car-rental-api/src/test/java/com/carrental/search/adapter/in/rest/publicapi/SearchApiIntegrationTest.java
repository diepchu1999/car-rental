package com.carrental.search.adapter.in.rest.publicapi;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.search.SearchIntegrationTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP thật → search → directory → PostgreSQL thật; không Authorization, không mock use case.
 * Fixture commit vì server chạy luồng khác; model riêng cô lập dữ liệu, container đóng sau lớp.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT1H")
@Import({PostgresTestConfiguration.class, SearchIntegrationTestConfiguration.class,
        SearchApiIntegrationTest.HttpConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchApiIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private HttpClient httpClient;
    @Autowired private ObjectMapper mapper;
    @Autowired private SearchIntegrationTestConfiguration.Fixtures fixtures;
    @Autowired private AvailabilityDirectory availability;
    private Map<String, String> parameters;
    private String model;

    /** Khoảng 10:00–16:00 Việt Nam, hợp lệ so với Clock cố định của fixture. */
    @BeforeEach
    void setUp() {
        model = "HttpModel-" + UUID.randomUUID();
        parameters = new LinkedHashMap<>();
        parameters.put("latitude", "10.76");
        parameters.put("longitude", "106.66");
        parameters.put("startInclusive", "2030-01-15T03:00:00Z");
        parameters.put("endExclusive", "2030-01-15T09:00:00Z");
        parameters.put("rentalType", "DAILY");
        parameters.put("driveMode", "SELF_DRIVE");
        parameters.put("model", model);
    }

    /** BR-010/112/125/808: API public hoạt động không token, đủ trường, không lộ ID hoặc sở hữu. */
    @Test
    void returnsOnlyPublicFieldsAndAppliesDefaults() throws Exception {
        var near = fixtures.branch(10.761, 106.66);
        var origin = fixtures.branch(10.76, 106.66);
        var far = fixtures.branch(11.76, 106.66);
        var fartherVehicle = fixtures.active(near.code(), model);
        var nearestVehicle = fixtures.active(origin.code(), model);
        fixtures.draft(origin.code(), model);
        fixtures.active(far.code(), model);
        var data = success(get());
        assertEquals(2, data.path("items").size());
        assertTrue(data.path("nextCursor").isNull());
        var first = data.path("items").get(0);
        assertEquals(nearestVehicle.code(), first.path("code").asString());
        assertEquals(fartherVehicle.code(), data.path("items").get(1).path("code").asString());
        assertEquals(Set.of("code", "make", "model", "seats", "transmission", "fuelType",
                "collateralFree", "branch", "distanceMeters"), Set.copyOf(first.propertyNames()));
        assertEquals("Toyota", first.path("make").asString());
        assertEquals(model, first.path("model").asString());
        assertEquals(5, first.path("seats").intValue());
        assertEquals("AUTOMATIC", first.path("transmission").asString());
        assertEquals("PETROL", first.path("fuelType").asString());
        assertTrue(first.path("collateralFree").booleanValue());
        assertEquals(0.0, first.path("distanceMeters").doubleValue(), 0.000001);
        assertEquals(Set.of("code", "name", "address"), Set.copyOf(first.path("branch").propertyNames()));
        assertEquals(origin.code(), first.path("branch").path("code").asString());
        assertEquals(origin.name(), first.path("branch").path("name").asString());
        assertEquals(origin.address(), first.path("branch").path("address").asString());
        parameters.put("pickupMethod", "BRANCH");
        parameters.put("sort", "NEAREST");
        parameters.put("radiusKm", "10");
        parameters.put("limit", "20");
        assertEquals(data, success(get()));
    }

    /** BR-126/110: tất cả bộ lọc đi từ URL đến SQL/policy; dấu cách và offset được URL-encode. */
    @Test
    void bindsFiltersAndOffsetTimestamps() throws Exception {
        var branch = fixtures.branch(10.76, 106.66);
        var vehicle = fixtures.active(branch.code(), model);
        parameters.put("startInclusive", "2030-01-15T10:00:00+07:00");
        parameters.put("endExclusive", "2030-01-15T16:00:00+07:00");
        parameters.put("seats", "5");
        parameters.put("transmission", "AUTOMATIC");
        parameters.put("fuelType", "PETROL");
        parameters.put("make", "  tOyOtA  ");
        parameters.put("model", " " + model.toLowerCase(Locale.ROOT) + " ");
        parameters.put("collateralFree", "true");
        assertEquals(vehicle.code(), success(get()).path("items").get(0).path("code").asString());
        parameters.put("collateralFree", "false");
        assertTrue(success(get()).path("items").isEmpty());
    }

    /** BR-104/015: khóa thuê và giấy tờ thực sự loại xe khỏi response HTTP. */
    @Test
    void hidesHeldAndComplianceBlockedVehicles() throws Exception {
        var branch = fixtures.branch(10.76, 106.66);
        var free = fixtures.active(branch.code(), model);
        var held = fixtures.active(branch.code(), model);
        var expired = fixtures.active(branch.code(), model);
        Instant start = Instant.parse(parameters.get("startInclusive"));
        Instant end = Instant.parse(parameters.get("endExclusive"));
        availability.hold(held.id(), new Period(start, end), Duration.ofHours(2), "http-booking");
        availability.block(expired.id(), new Period(start.minusSeconds(1), null),
                BlockKind.COMPLIANCE_HOLD, "Expired inspection");
        var items = success(get()).path("items");
        assertEquals(1, items.size());
        assertEquals(free.code(), items.get(0).path("code").asString());
    }

    /** Cursor lấy từ JSON dùng trực tiếp cho trang sau; đổi điều kiện với cursor cũ bị từ chối. */
    @Test
    void followsCursorWithoutRepeatingVehiclesAndRejectsDifferentQuery() throws Exception {
        var branch = fixtures.branch(10.76, 106.66);
        var first = fixtures.active(branch.code(), model);
        var second = fixtures.active(branch.code(), model);
        parameters.put("limit", "1");
        var page1 = success(get());
        assertEquals(1, page1.path("items").size());
        assertEquals(first.code(), page1.path("items").get(0).path("code").asString());
        assertTrue(page1.path("nextCursor").isString());
        String cursor = page1.path("nextCursor").asString();
        assertFalse(cursor.isBlank());
        parameters.put("cursor", cursor);
        var page2 = success(get());
        assertEquals(1, page2.path("items").size());
        assertEquals(second.code(), page2.path("items").get(0).path("code").asString());
        assertTrue(page2.path("nextCursor").isNull());
        parameters.put("radiusKm", "5");
        error(get(), 400, "INVALID_REQUEST");
    }

    /** Tập rỗng là 200 với items rỗng và nextCursor null, không phải 404. */
    @Test
    void returnsAnEmptyPageWhenNoVehiclesMatch() throws Exception {
        var data = success(get());
        assertTrue(data.path("items").isEmpty());
        assertTrue(data.path("nextCursor").isNull());
    }

    /** BR-125: từng đầu vào bắt buộc bị thiếu đều ra 400, không dùng tọa độ/thời gian ngầm định. */
    @ParameterizedTest
    @ValueSource(strings = {"latitude", "longitude", "startInclusive", "endExclusive", "rentalType", "driveMode"})
    void rejectsMissingRequiredParameters(String field) throws Exception {
        parameters.remove(field);
        error(get(), 400, "INVALID_REQUEST");
    }

    /** Lỗi chuyển kiểu, miền giá trị, trường lạ và giá trị rỗng không được nuốt thành mặc định. */
    @ParameterizedTest
    @CsvSource({
            "latitude,nope", "latitude,NaN", "latitude,91", "longitude,181", "longitude,Infinity",
            "startInclusive,2030-01-15", "endExclusive,2030-01-15T03:00:00Z",
            "rentalType,UNKNOWN", "driveMode,UNKNOWN", "pickupMethod,UNKNOWN", "sort,UNKNOWN",
            "radiusKm,31", "radiusKm,0", "radiusKm,-1", "radiusKm,NaN",
            "limit,101", "limit,0", "limit,-1", "limit,1.5", "limit,999999999999999",
            "seats,five", "transmission,CVT", "fuelType,WATER", "collateralFree,maybe",
            "cursor,broken", "ownershipType,COMPANY", "minPrice,100",
            "radiusKm,''", "limit,''", "seats,''", "collateralFree,''", "make,' '", "cursor,''"
    })
    void rejectsInvalidParameters(String field, String value) throws Exception {
        parameters.put(field, value);
        error(get(), 400, "INVALID_REQUEST");
    }

    /** Không chọn ngầm giá trị đầu/cuối khi khách gửi cùng một tham số hai lần. */
    @Test
    void rejectsDuplicateParameters() throws Exception {
        error(getWithSuffix("&latitude=11"), 400, "INVALID_REQUEST");
    }

    /** Lựa chọn đúng enum nhưng chưa hỗ trợ phải ra đúng lỗi 422, không fallback sang tìm kiếm khác. */
    @ParameterizedTest
    @CsvSource({
            "rentalType,MONTHLY,SEARCH_RENTAL_TYPE_NOT_SUPPORTED",
            "driveMode,WITH_DRIVER,SEARCH_DRIVE_MODE_NOT_SUPPORTED",
            "pickupMethod,DELIVERY,SEARCH_PICKUP_METHOD_NOT_SUPPORTED",
            "sort,PRICE_ASC,SEARCH_SORT_NOT_SUPPORTED",
            "sort,RATING_DESC,SEARCH_SORT_NOT_SUPPORTED",
            "seats,6,VEHICLE_INVALID_SEATS"
    })
    void returnsSpecificRuleViolation(String field, String value, String code) throws Exception {
        parameters.put(field, value);
        error(get(), 422, code);
    }

    /** BR-113/119/121: kiểm đủ bốn nhóm lỗi điều kiện thuê qua HTTP và múi giờ ứng dụng. */
    @ParameterizedTest
    @CsvSource({
            "HOURLY,2030-01-15T03:00:00Z,2030-01-15T06:00:00Z,RENTAL_DURATION_TOO_SHORT",
            "DAILY,2030-01-15T22:00:00Z,2030-01-16T09:00:00Z,OUTSIDE_BRANCH_HOURS",
            "DAILY,2030-01-15T00:30:00Z,2030-01-15T09:00:00Z,BOOKING_WINDOW_VIOLATION",
            "DAILY,2030-08-15T03:00:00Z,2030-08-15T09:00:00Z,BOOKING_WINDOW_VIOLATION"
    })
    void rejectsRentalTermsThroughHttp(String type, String start, String end, String code) throws Exception {
        parameters.put("rentalType", type);
        parameters.put("startInclusive", start);
        parameters.put("endExclusive", end);
        error(get(), 422, code);
    }

    /** Gọi endpoint bằng HTTP thật, không gửi thông tin đăng nhập. */
    private HttpResponse<String> get() throws Exception {
        return getWithSuffix("");
    }

    /** Encode từng giá trị, đặc biệt dấu '+' của offset; suffix chỉ dùng fixture tham số lặp. */
    private HttpResponse<String> getWithSuffix(String suffix) throws Exception {
        String query = parameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/public/vehicles?" + query + suffix))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("X-Client-Platform", "web").header("X-Client-Version", "0.0.1").GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Encode dữ liệu URL theo UTF-8, không nối chuỗi người dùng trực tiếp vào URL. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Kiểm cấu trúc chung và Content-Type trước khi xem dữ liệu cụ thể. */
    private JsonNode envelope(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        var root = mapper.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), Set.copyOf(root.propertyNames()));
        return root;
    }

    /** Khóa hợp đồng trang và cấm trường sở hữu ở mọi độ sâu của response thành công theo BR-112. */
    private JsonNode success(HttpResponse<String> response) {
        var root = envelope(response, 200);
        assertTrue(root.path("success").booleanValue());
        assertTrue(root.path("error").isNull());
        assertFalse(response.body().toLowerCase(Locale.ROOT).contains("ownership"));
        var data = root.path("data");
        assertEquals(Set.of("items", "nextCursor"), Set.copyOf(data.propertyNames()));
        assertTrue(data.path("items").isArray());
        return data;
    }

    /** Kiểm đúng mã lỗi ổn định, không chấp nhận HTTP lỗi bất kỳ làm bằng chứng. */
    private void error(HttpResponse<String> response, int status, String code) {
        var root = envelope(response, status);
        assertFalse(root.path("success").booleanValue());
        assertTrue(root.path("data").isNull());
        assertEquals(Set.of("code", "message"), Set.copyOf(root.path("error").propertyNames()));
        assertEquals(code, root.path("error").path("code").asString());
        assertFalse(root.path("error").path("message").asString().isBlank());
    }

    /** Tách context HTTP của lớp này và quản lý vòng đời client cùng server/container. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HttpConfiguration {
        /** Client có timeout và không tự theo redirect để tránh che lỗi routing. */
        @Bean(destroyMethod = "close")
        HttpClient searchApiHttpClient() {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
    }
}
