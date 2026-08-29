package com.carrental.branch.adapter.in.rest.admin;

import com.carrental.PostgresTestConfiguration;
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
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra hợp đồng HTTP của API quản trị chi nhánh trên server thật.
 *
 * <p>Vị trí chi nhánh phục vụ BR-003. Các kiểm tra HTTP tuân theo
 * danh mục API chi nhánh: mã trạng thái, Location và cấu trúc JSON.
 *
 * <p>Cấu hình HTTP client riêng tạo application context riêng,
 * qua đó tách container PostgreSQL khỏi những nhóm integration test khác.
 *
 * <p>Không dùng transaction trên lớp test vì yêu cầu HTTP được xử lý
 * trên luồng của server. Dữ liệu được commit thật và tồn tại
 * cho đến khi context cùng container của lớp test được đóng.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({
        PostgresTestConfiguration.class,
        BranchApiIntegrationTest.HttpClientConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BranchApiIntegrationTest {

    private static final String BRANCHES_PATH = "/api/v1/admin/branches";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private HttpClient httpClient;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Chứng minh POST tạo được chi nhánh và Location đọc lại đúng tài nguyên.
     *
     * <p>Kiểm cả tọa độ tại Việt Nam và tọa độ bằng không
     * để tránh nhầm giá trị không với dữ liệu bị thiếu.
     *
     * @param latitude vĩ độ hợp lệ của chi nhánh
     * @param longitude kinh độ hợp lệ của chi nhánh
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    @ParameterizedTest
    @CsvSource({
            "10.762622, 106.660172",
            "0.0, 0.0"
    })
    void createsBranchAndReadsItThroughLocation(
            double latitude,
            double longitude
    ) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "latitude", latitude,
                        "longitude", longitude
                )
        );

        HttpResponse<String> createResponse = post(
                requestBody,
                MediaType.APPLICATION_JSON_VALUE
        );

        JsonNode createdBranch = assertSuccessfulResponse(
                createResponse,
                201
        );

        assertEquals(
                latitude,
                createdBranch.path("latitude").doubleValue(),
                0.000000001
        );
        assertEquals(
                longitude,
                createdBranch.path("longitude").doubleValue(),
                0.000000001
        );

        String code = createdBranch.path("code").asString();

        String location = createResponse.headers()
                .firstValue("Location")
                .orElseThrow(
                        () -> new AssertionError(
                                "The creation response must include Location."
                        )
                );

        assertEquals(BRANCHES_PATH + "/" + code, location);

        URI resourceUri = uri(BRANCHES_PATH).resolve(location);
        HttpResponse<String> getResponse = get(resourceUri);

        JsonNode loadedBranch = assertSuccessfulResponse(
                getResponse,
                200
        );

        assertEquals(createdBranch, loadedBranch);
    }

    /**
     * Chứng minh dữ liệu đầu vào không hợp lệ trả HTTP 400 theo hợp đồng lỗi.
     *
     * <p>Các trường hợp gồm thiếu trường, null, tọa độ vượt giới hạn,
     * sai kiểu dữ liệu, JSON hỏng và thiếu nội dung yêu cầu.
     *
     * @param requestBody nội dung yêu cầu không hợp lệ
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"longitude\":106.660172}",
            "{\"latitude\":10.762622}",
            "{\"latitude\":null,\"longitude\":106.660172}",
            "{\"latitude\":10.762622,\"longitude\":null}",
            "{\"latitude\":91,\"longitude\":106.660172}",
            "{\"latitude\":-91,\"longitude\":106.660172}",
            "{\"latitude\":10.762622,\"longitude\":181}",
            "{\"latitude\":10.762622,\"longitude\":-181}",
            "{\"latitude\":\"not-a-number\",\"longitude\":106.660172}",
            "{",
            "null",
            ""
    })
    void rejectsInvalidCreateRequests(
            String requestBody
    ) throws IOException, InterruptedException {
        HttpResponse<String> response = post(
                requestBody,
                MediaType.APPLICATION_JSON_VALUE
        );

        assertErrorResponse(response, 400, "INVALID_REQUEST");
    }

    /**
     * Chứng minh mã chi nhánh không tồn tại trả HTTP 404 và lỗi nghiệp vụ.
     *
     * <p>Mã truy vấn không cần tuân theo định dạng sinh mã khi tạo.
     * Giá trị này không thể trùng với mã do ứng dụng tự sinh.
     *
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    @Test
    void returnsNotFoundForMissingBranch()
            throws IOException, InterruptedException {
        HttpResponse<String> response = get(
                uri(BRANCHES_PATH + "/missing-branch")
        );

        JsonNode error = assertErrorResponse(
                response,
                404,
                "BRANCH_NOT_FOUND"
        );

        assertEquals(
                "Branch not found.",
                error.path("message").asString()
        );
    }

    /**
     * Chứng minh API từ chối Content-Type không được hỗ trợ.
     *
     * <p>Nội dung là JSON hợp lệ nhưng khai báo text/plain,
     * nên server phải trả HTTP 415 thay vì xử lý như yêu cầu JSON.
     *
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    @Test
    void rejectsUnsupportedContentType()
            throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "latitude", 10.762622,
                        "longitude", 106.660172
                )
        );

        HttpResponse<String> response = post(
                requestBody,
                MediaType.TEXT_PLAIN_VALUE
        );

        assertErrorResponse(response, 415, "INVALID_REQUEST");
    }

    /**
     * Gửi yêu cầu tạo chi nhánh qua kết nối HTTP thật.
     *
     * @param requestBody nội dung gửi nguyên văn tới server
     * @param contentType loại nội dung được khai báo trong yêu cầu
     * @return response gồm mã trạng thái, header và nội dung UTF-8
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    private HttpResponse<String> post(
            String requestBody,
            String contentType
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(BRANCHES_PATH))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Content-Type", contentType)
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                requestBody,
                                StandardCharsets.UTF_8
                        )
                )
                .build();

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    /**
     * Gửi yêu cầu đọc tài nguyên qua kết nối HTTP thật.
     *
     * @param resourceUri địa chỉ tài nguyên cần đọc
     * @return response gồm mã trạng thái, header và nội dung UTF-8
     * @throws IOException nếu không trao đổi được dữ liệu HTTP
     * @throws InterruptedException nếu luồng chờ HTTP bị ngắt
     */
    private HttpResponse<String> get(
            URI resourceUri
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(resourceUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .GET()
                .build();

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    /**
     * Tạo địa chỉ HTTP sử dụng cổng thực tế do Spring Boot cấp cho test.
     *
     * @param path đường dẫn tuyệt đối bên trong ứng dụng
     * @return địa chỉ của server đang chạy trong lớp test
     */
    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /**
     * Kiểm mã HTTP, loại nội dung và cấu trúc ngoài của response API.
     *
     * @param response response nhận được từ server
     * @param expectedStatus mã HTTP được hợp đồng quy định
     * @return đối tượng JSON đã kiểm tra cấu trúc ngoài
     */
    private JsonNode readApiResponse(
            HttpResponse<String> response,
            int expectedStatus
    ) {
        assertEquals(
                expectedStatus,
                response.statusCode(),
                response.body()
        );

        String contentType = response.headers()
                .firstValue("Content-Type")
                .orElseThrow(
                        () -> new AssertionError(
                                "The API response must include Content-Type."
                        )
                );

        assertTrue(
                MediaType.APPLICATION_JSON.isCompatibleWith(
                        MediaType.parseMediaType(contentType)
                ),
                "The API response must use application/json."
        );

        JsonNode root = objectMapper.readTree(response.body());

        assertNotNull(root);
        assertTrue(root.isObject());
        assertEquals(
                Set.of("success", "data", "error"),
                Set.copyOf(root.propertyNames())
        );
        assertTrue(root.path("success").isBoolean());

        return root;
    }

    /**
     * Kiểm cấu trúc response thành công và dữ liệu chi nhánh công khai.
     *
     * <p>Danh sách trường phải khớp chính xác với hợp đồng,
     * nhờ đó phát hiện cả việc vô tình trả thêm khóa chính nội bộ.
     *
     * @param response response nhận được từ server
     * @param expectedStatus mã HTTP thành công được mong đợi
     * @return dữ liệu chi nhánh đã kiểm tra cấu trúc và kiểu JSON
     */
    private JsonNode assertSuccessfulResponse(
            HttpResponse<String> response,
            int expectedStatus
    ) {
        JsonNode root = readApiResponse(response, expectedStatus);

        assertTrue(root.path("success").booleanValue());
        assertTrue(root.path("error").isNull());

        JsonNode data = root.path("data");

        assertTrue(data.isObject());
        assertEquals(
                Set.of("code", "latitude", "longitude"),
                Set.copyOf(data.propertyNames())
        );

        assertTrue(data.path("code").isString());
        assertTrue(
                data.path("code").asString().matches("CN-[A-Z0-9]{6}")
        );
        assertTrue(data.path("latitude").isNumber());
        assertTrue(data.path("longitude").isNumber());

        return data;
    }

    /**
     * Kiểm cấu trúc response lỗi và mã lỗi ổn định dành cho client.
     *
     * @param response response nhận được từ server
     * @param expectedStatus mã HTTP lỗi được mong đợi
     * @param expectedCode mã lỗi được hợp đồng quy định
     * @return đối tượng lỗi để từng test kiểm thêm thông tin cần thiết
     */
    private JsonNode assertErrorResponse(
            HttpResponse<String> response,
            int expectedStatus,
            String expectedCode
    ) {
        JsonNode root = readApiResponse(response, expectedStatus);

        assertFalse(root.path("success").booleanValue());
        assertTrue(root.path("data").isNull());

        JsonNode error = root.path("error");

        assertTrue(error.isObject());
        assertEquals(
                Set.of("code", "message"),
                Set.copyOf(error.propertyNames())
        );

        assertTrue(error.path("code").isString());
        assertTrue(error.path("message").isString());
        assertEquals(expectedCode, error.path("code").asString());
        assertFalse(error.path("message").asString().isBlank());

        return error;
    }

    /**
     * Cung cấp HTTP client riêng cho nhóm test API chi nhánh.
     *
     * <p>Cấu hình được import riêng giúp tách application context
     * và container PostgreSQL khỏi các nhóm test khác.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HttpClientConfiguration {

        /**
         * Tạo client có giới hạn thời gian kết nối và không tự chuyển hướng.
         *
         * <p>Test tự kiểm Location trước khi gửi GET.
         * Spring đóng client khi application context kết thúc.
         *
         * @return HTTP client dùng cho các yêu cầu kiểm thử
         */
        @Bean(destroyMethod = "close")
        HttpClient branchApiHttpClient() {
            return HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
    }
}