package com.carrental.vehicle.adapter.in.rest.admin;

import com.carrental.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm hợp đồng API xe qua HTTP, service và PostgreSQL thật.
 *
 * <p>Phủ BR-001, BR-003, BR-005, BR-010, BR-410 và response envelope.
 * Không dùng transaction trên test vì request chạy trên luồng server.
 * Cấu hình riêng tách context/container và đóng sau lớp test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, VehicleApiIntegrationTest.HttpClientConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VehicleApiIntegrationTest {

    private static final String PATH = "/api/v1/admin/vehicles";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @LocalServerPort private int port;
    @Autowired private HttpClient client;
    @Autowired private ObjectMapper mapper;
    @Autowired private Clock clock;

    /**
     * Tạo và đọc bản nháp với đủ bốn loại nhiên liệu theo BR-410, chưa cần giấy tờ.
     * @param fuel loại nhiên liệu hợp lệ
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @ParameterizedTest
    @ValueSource(strings = {"PETROL", "DIESEL", "ELECTRIC", "HYBRID"})
    void createsDraftAndReadsThroughLocation(String fuel) throws Exception {
        Map<String, Object> body = payload(branch(), fuel, null, null);
        HttpResponse<String> response = post(PATH, body);
        JsonNode created = vehicleSuccess(response, 201, "DRAFT");
        assertEquals(body.get("plateNumber"), created.path("plateNumber").asString());
        assertEquals(fuel, created.path("fuelType").asString());
        assertEquals(5, created.path("seats").intValue());
        assertEquals("AUTOMATIC", created.path("transmission").asString());
        assertEquals("Toyota", created.path("make").asString());
        assertEquals("Vios", created.path("model").asString());
        assertEquals("COMPANY", created.path("ownershipType").asString());
        assertEquals(body.get("branchCode"), created.path("branchCode").asString());
        assertTrue(created.path("inspectionExpiresOn").isNull());
        assertTrue(created.path("liabilityInsuranceExpiresOn").isNull());
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(PATH + "/" + created.path("code").asString(), location);
        assertEquals(created, vehicleSuccess(get(location), 200, "DRAFT"));
    }

    /**
     * Chứng minh cả ba bước vòng đời qua HTTP và giữ nguyên dữ liệu sau mỗi bước.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void createsSubmitsAndApprovesVehicle() throws Exception {
        LocalDate expiry = LocalDate.now(clock).plusDays(30);
        String branchCode = branch();
        JsonNode created = vehicleSuccess(post(PATH, payload(branchCode, "HYBRID", expiry, expiry.plusDays(1))),
                201, "DRAFT");
        assertEquals(branchCode, created.path("branchCode").asString());
        String resource = PATH + "/" + created.path("code").asString();

        JsonNode pending = vehicleSuccess(postEmpty(resource + "/submit-for-approval"), 200, "PENDING_APPROVAL");
        assertSameVehicleFields(created, pending);
        JsonNode active = vehicleSuccess(postEmpty(resource + "/approve"), 200, "ACTIVE");
        assertSameVehicleFields(created, active);
        assertEquals(active, vehicleSuccess(get(resource), 200, "ACTIVE"));
        assertEquals(expiry.toString(), active.path("inspectionExpiresOn").asString());
        assertEquals(expiry.plusDays(1).toString(), active.path("liabilityInsuranceExpiresOn").asString());

        error(postEmpty(resource + "/approve"), 422, "VEHICLE_INVALID_STATUS_TRANSITION");
        assertEquals(active, vehicleSuccess(get(resource), 200, "ACTIVE"));
    }

    /**
     * Client không thể tự chọn PARTNER, ACTIVE hoặc mã xe bằng trường JSON bổ sung.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void ignoresClientControlledOwnershipAndStatus() throws Exception {
        Map<String, Object> body = payload(branch(), "PETROL", null, null);
        body.put("ownershipType", "PARTNER");
        body.put("status", "ACTIVE");
        body.put("code", "client-selected-code");
        body.put("branchId", -1);
        body.put("id", -1);
        JsonNode created = vehicleSuccess(post(PATH, body), 201, "DRAFT");
        assertEquals("COMPANY", created.path("ownershipType").asString());
        assertNotEquals("client-selected-code", created.path("code").asString());
        assertEquals(body.get("branchCode"), created.path("branchCode").asString());
    }

    /**
     * Trùng biển số phải trả 409 và không sửa dữ liệu xe cũ.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void rejectsDuplicatePlate() throws Exception {
        Map<String, Object> body = payload(branch(), "PETROL", null, null);
        JsonNode first = vehicleSuccess(post(PATH, body), 201, "DRAFT");
        error(post(PATH, body), 409, "VEHICLE_PLATE_ALREADY_EXISTS");
        assertEquals(first, vehicleSuccess(get(PATH + "/" + first.path("code").asString()), 200, "DRAFT"));
    }

    /**
     * Không được tạo xe gắn với mã chi nhánh không tồn tại theo BR-003.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void rejectsMissingBranch() throws Exception {
        error(post(PATH, payload("missing-branch", "PETROL", null, null)), 404, "BRANCH_NOT_FOUND");
    }

    /**
     * Các đường đọc, gửi duyệt và duyệt đều phải trả 404 cho mã không tồn tại.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void rejectsMissingVehicleOnEveryEndpoint() throws Exception {
        error(get(PATH + "/missing-vehicle"), 404, "VEHICLE_NOT_FOUND");
        error(postEmpty(PATH + "/missing-vehicle/submit-for-approval"), 404, "VEHICLE_NOT_FOUND");
        error(postEmpty(PATH + "/missing-vehicle/approve"), 404, "VEHICLE_NOT_FOUND");
    }

    /**
     * Không được bỏ qua gửi duyệt hoặc gửi duyệt hai lần theo status-flow mục 4.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void rejectsSkippedAndRepeatedTransitions() throws Exception {
        LocalDate expiry = LocalDate.now(clock).plusDays(30);
        JsonNode draft = vehicleSuccess(post(PATH, payload(branch(), "DIESEL", expiry, expiry)), 201, "DRAFT");
        String resource = PATH + "/" + draft.path("code").asString();
        error(postEmpty(resource + "/approve"), 422, "VEHICLE_INVALID_STATUS_TRANSITION");
        assertEquals(draft, vehicleSuccess(get(resource), 200, "DRAFT"));
        JsonNode pending = vehicleSuccess(postEmpty(resource + "/submit-for-approval"), 200, "PENDING_APPROVAL");
        error(postEmpty(resource + "/submit-for-approval"), 422, "VEHICLE_INVALID_STATUS_TRANSITION");
        assertEquals(pending, vehicleSuccess(get(resource), 200, "PENDING_APPROVAL"));
    }

    /**
     * Thiếu một hoặc cả hai ngày vẫn tạo được bản nháp nhưng không được duyệt theo BR-005.
     * @param missing trường ngày bị thiếu
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @ParameterizedTest
    @ValueSource(strings = {"inspection", "insurance", "both"})
    void rejectsMissingDocumentsAtApproval(String missing) throws Exception {
        LocalDate future = LocalDate.now(clock).plusDays(30);
        LocalDate inspection = missing.equals("insurance") ? future : null;
        LocalDate insurance = missing.equals("inspection") ? future : null;
        assertApprovalFailure(payload(branch(), "PETROL", inspection, insurance), "VEHICLE_DOCUMENT_MISSING");
    }

    /**
     * Giấy tờ hết hạn hôm nay hoặc trước đó phải bị chặn tại cổng duyệt theo BR-005.
     * @param expired giấy tờ hết hạn và biên ngày cần kiểm
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @ParameterizedTest
    @ValueSource(strings = {"inspection-today", "insurance-today", "both-past"})
    void rejectsExpiredDocumentsAtApproval(String expired) throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate inspection = expired.equals("insurance-today") ? today.plusDays(30) : today;
        LocalDate insurance = expired.equals("inspection-today") ? today.plusDays(30) : today;
        if (expired.equals("both-past")) {
            inspection = today.minusDays(2);
            insurance = today.minusDays(2);
        }
        assertApprovalFailure(payload(branch(), "ELECTRIC", inspection, insurance), "VEHICLE_DOCUMENT_EXPIRED");
    }

    /**
     * Kiểm từng trường bắt buộc và dữ liệu JSON sai định dạng.
     * @param invalid kịch bản đầu vào không hợp lệ
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing-plate", "null-plate", "blank-plate", "missing-fuel", "null-fuel",
            "invalid-fuel", "missing-branch", "null-branch", "blank-branch", "invalid-date"})
    void rejectsInvalidFields(String invalid) throws Exception {
        Map<String, Object> body = payload("CN-INPUT1", "PETROL", null, null);
        switch (invalid) {
            case "missing-plate" -> body.remove("plateNumber");
            case "null-plate" -> body.put("plateNumber", null);
            case "blank-plate" -> body.put("plateNumber", "   ");
            case "missing-fuel" -> body.remove("fuelType");
            case "null-fuel" -> body.put("fuelType", null);
            case "invalid-fuel" -> body.put("fuelType", "UNKNOWN");
            case "missing-branch" -> body.remove("branchCode");
            case "null-branch" -> body.put("branchCode", null);
            case "blank-branch" -> body.put("branchCode", "   ");
            case "invalid-date" -> body.put("inspectionExpiresOn", "not-a-date");
            default -> throw new AssertionError("Unknown invalid input scenario.");
        }
        error(post(PATH, body), 400, "INVALID_REQUEST");
    }

    /** BR-018: thiếu hoặc null bất kỳ thuộc tính mới nào đều bị từ chối trước khi tra cứu chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"seats", "transmission", "make", "model"})
    void rejectsMissingOrNullSpecifications(String field) throws Exception {
        Map<String, Object> body = payload("CN-INPUT1", "PETROL", null, null);
        body.remove(field);
        error(post(PATH, body), 400, "INVALID_REQUEST");
        body.put(field, null);
        error(post(PATH, body), 400, "INVALID_REQUEST");
    }

    /** BR-018: chuỗi trắng và hộp số ngoài enum không được lưu vào CSDL. */
    @ParameterizedTest
    @ValueSource(strings = {"make", "model", "transmission"})
    void rejectsInvalidSpecificationText(String field) throws Exception {
        Map<String, Object> body = payload("CN-INPUT1", "PETROL", null, null);
        body.put(field, " \t\n");
        error(post(PATH, body), 400, "INVALID_REQUEST");
        body.put(field, "");
        error(post(PATH, body), 400, "INVALID_REQUEST");
        if (field.equals("transmission")) {
            body.put(field, "CVT");
            error(post(PATH, body), 400, "INVALID_REQUEST");
        }
    }

    /** BR-018: số chỗ 6 đúng kiểu số nhưng sai tập nghiệp vụ, trả 422 trước khi tra cứu chi nhánh. */
    @Test
    void rejectsUnsupportedSeats() throws Exception {
        Map<String, Object> body = payload("CN-INPUT1", "PETROL", null, null);
        body.put("seats", 6);
        error(post(PATH, body), 422, "VEHICLE_INVALID_SEATS");
    }

    /** BR-018: mọi số chỗ hợp lệ cùng cả hai hộp số đều được lưu và đọc lại nguyên trạng. */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 16})
    void roundTripsSpecifications(int seats) throws Exception {
        String branchCode = branch();
        for (String transmission : new String[]{"MANUAL", "AUTOMATIC"}) {
            Map<String, Object> body = payload(branchCode, "PETROL", null, null);
            body.put("seats", seats);
            body.put("transmission", transmission);
            body.put("make", " Toyota ");
            body.put("model", "Model " + "x".repeat(1000));
            HttpResponse<String> response = post(PATH, body);
            JsonNode created = vehicleSuccess(response, 201, "DRAFT");
            assertEquals(seats, created.path("seats").intValue());
            assertEquals(transmission, created.path("transmission").asString());
            assertEquals(body.get("make"), created.path("make").asString());
            assertEquals(body.get("model"), created.path("model").asString());
            assertEquals(created, vehicleSuccess(
                    get(response.headers().firstValue("Location").orElseThrow()), 200, "DRAFT"));
        }
    }

    /**
     * JSON hỏng, null và thiếu body phải trả lỗi chuẩn thay vì 500.
     * @param body nội dung không hợp lệ
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @ParameterizedTest
    @ValueSource(strings = {"{", "null", ""})
    void rejectsMalformedOrMissingBody(String body) throws Exception {
        error(send("POST", PATH, body, "application/json"), 400, "INVALID_REQUEST");
    }

    /**
     * Content-Type không hỗ trợ phải giữ mã 415 và response envelope chung.
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    @Test
    void rejectsUnsupportedContentType() throws Exception {
        error(send("POST", PATH, "{}", "text/plain"), 415, "INVALID_REQUEST");
    }

    /**
     * Kiểm lỗi duyệt và chứng minh hồ sơ vẫn ở PENDING_APPROVAL sau lỗi.
     * @param body request tạo bản nháp
     * @param code mã lỗi giấy tờ mong đợi
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    private void assertApprovalFailure(Map<String, Object> body, String code) throws Exception {
        JsonNode draft = vehicleSuccess(post(PATH, body), 201, "DRAFT");
        String resource = PATH + "/" + draft.path("code").asString();
        JsonNode pending = vehicleSuccess(postEmpty(resource + "/submit-for-approval"), 200, "PENDING_APPROVAL");
        error(postEmpty(resource + "/approve"), 422, code);
        assertEquals(pending, vehicleSuccess(get(resource), 200, "PENDING_APPROVAL"));
    }

    /**
     * Tạo chi nhánh thật bằng API để kiểm trọn lát cắt liên module.
     * @return mã chi nhánh vừa tạo
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    private String branch() throws Exception {
        HttpResponse<String> response = post("/api/v1/admin/branches",
                Map.of("latitude", 10.762622, "longitude", 106.660172,
                        "name", "Vehicle Test Branch", "address", "123 Test Street"));
        JsonNode root = envelope(response, 201);
        assertTrue(root.path("success").booleanValue());
        return root.path("data").path("code").asString();
    }

    /**
     * Tạo request có biển số riêng và cho phép ngày giấy tờ null.
     * @param branchCode mã chi nhánh
     * @param fuel loại nhiên liệu
     * @param inspection hạn đăng kiểm
     * @param insurance hạn TNDS
     * @return map có thể chỉnh cho test âm
     */
    private static Map<String, Object> payload(String branchCode, String fuel,
                                              LocalDate inspection, LocalDate insurance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plateNumber", "TEST-" + UUID.randomUUID());
        body.put("fuelType", fuel);
        body.put("seats", 5);
        body.put("transmission", "AUTOMATIC");
        body.put("make", "Toyota");
        body.put("model", "Vios");
        body.put("branchCode", branchCode);
        body.put("inspectionExpiresOn", inspection == null ? null : inspection.toString());
        body.put("liabilityInsuranceExpiresOn", insurance == null ? null : insurance.toString());
        return body;
    }

    /**
     * Gửi JSON qua HTTP thật.
     * @param path đường dẫn endpoint
     * @param body dữ liệu JSON
     * @return response từ server
     * @throws Exception nếu gửi hoặc đọc response thất bại
     */
    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        return send("POST", path, mapper.writeValueAsString(body), "application/json");
    }

    /**
     * Gửi lệnh chuyển trạng thái không có body.
     * @param path đường dẫn endpoint
     * @return response từ server
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    private HttpResponse<String> postEmpty(String path) throws Exception {
        return send("POST", path, null, null);
    }

    /**
     * Đọc tài nguyên qua HTTP.
     * @param path đường dẫn endpoint
     * @return response từ server
     * @throws Exception nếu trao đổi HTTP thất bại
     */
    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    /**
     * Gửi request có timeout tới đúng cổng ngẫu nhiên của test.
     * @param method phương thức HTTP
     * @param path đường dẫn endpoint
     * @param body nội dung hoặc null khi không có body
     * @param contentType loại nội dung hoặc null
     * @return response UTF-8
     * @throws IOException nếu trao đổi HTTP thất bại
     * @throws InterruptedException nếu luồng chờ bị ngắt
     */
    private HttpResponse<String> send(String method, String path, String body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(TIMEOUT).header("Accept", "application/json");
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Kiểm mã HTTP, Content-Type và các trường ngoài của ApiResponse.
     * @param response response thực tế
     * @param status mã HTTP kỳ vọng
     * @return JSON envelope đã kiểm
     */
    private JsonNode envelope(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(
                response.headers().firstValue("Content-Type").orElseThrow())));
        JsonNode root = mapper.readTree(response.body());
        assertEquals(Set.of("success", "data", "error"), Set.copyOf(root.propertyNames()));
        assertTrue(root.path("success").isBoolean());
        return root;
    }

    /**
     * Kiểm chính xác hình dạng response xe, không trả nhầm application view có id.
     * @param response response thực tế
     * @param httpStatus mã HTTP kỳ vọng
     * @param vehicleStatus trạng thái xe kỳ vọng
     * @return dữ liệu xe
     */
    private JsonNode vehicleSuccess(HttpResponse<String> response, int httpStatus, String vehicleStatus) {
        JsonNode root = envelope(response, httpStatus);
        assertTrue(root.path("success").booleanValue());
        assertTrue(root.path("error").isNull());
        JsonNode data = root.path("data");
        assertEquals(Set.of("code", "plateNumber", "ownershipType", "fuelType", "branchCode", "status",
                "inspectionExpiresOn", "liabilityInsuranceExpiresOn", "seats", "transmission", "make", "model"), Set.copyOf(data.propertyNames()));
        assertTrue(data.path("code").asString().matches("XE-[A-Z0-9]{6}"));
        assertEquals(vehicleStatus, data.path("status").asString());
        assertTrue(data.path("plateNumber").isString());
        assertTrue(data.path("branchCode").asString().matches("CN-[A-Z0-9]{6}"));
        assertFalse(data.has("branchId"));
        assertFalse(data.has("id"));
        return data;
    }

    /**
     * Kiểm envelope lỗi và mã ổn định, không chỉ mã HTTP.
     * @param response response thực tế
     * @param status mã HTTP kỳ vọng
     * @param code mã lỗi kỳ vọng
     */
    private void error(HttpResponse<String> response, int status, String code) {
        JsonNode root = envelope(response, status);
        assertFalse(root.path("success").booleanValue());
        assertTrue(root.path("data").isNull());
        JsonNode error = root.path("error");
        assertEquals(Set.of("code", "message"), Set.copyOf(error.propertyNames()));
        assertEquals(code, error.path("code").asString());
        assertTrue(error.path("message").isString());
        assertFalse(error.path("message").asString().isBlank());
    }

    /**
     * Chứng minh chuyển trạng thái không thay đổi danh tính, chi nhánh và giấy tờ.
     * @param before dữ liệu trước thao tác
     * @param after dữ liệu sau thao tác
     */
    private static void assertSameVehicleFields(JsonNode before, JsonNode after) {
        for (String field : Set.of("code", "plateNumber", "ownershipType", "fuelType", "branchCode",
                "inspectionExpiresOn", "liabilityInsuranceExpiresOn", "seats", "transmission", "make", "model")) {
            assertEquals(before.path(field), after.path(field), field);
        }
    }

    /** Tạo HTTP client và context riêng cho test API xe. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HttpClientConfiguration {

        /**
         * Tạo client không tự chuyển hướng để test kiểm Location trực tiếp.
         * @return client do Spring đóng khi context kết thúc
         */
        @Bean(destroyMethod = "close")
        HttpClient vehicleApiHttpClient() {
            return HttpClient.newBuilder().connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
    }
}
