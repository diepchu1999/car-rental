package com.carrental.shared.error;

import com.carrental.shared.api.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm tra hợp đồng HTTP và JSON của bộ xử lý lỗi dùng chung.
 *
 * <p>Request đi qua Spring MVC với controller chỉ dành cho test.
 * Không khởi động ứng dụng Spring Boot hoặc kết nối CSDL.
 *
 * <p>Các lỗi được chủ động tạo để kiểm cách phản hồi.
 * Việc kiểm điều kiện nghiệp vụ thật thuộc test của từng module.
 */
class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    /**
     * Tạo môi trường MVC độc lập cho mỗi lượt test và đăng ký
     * bộ xử lý lỗi thật của ứng dụng.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * Cung cấp bốn nhóm lỗi và các mã điều kiện thuê cùng HTTP, thông báo mong đợi.
     *
     * <p>Giá trị mong đợi được viết tường minh để test phát hiện
     * thay đổi ngoài ý muốn của hợp đồng lỗi.
     *
     * @return dữ liệu kiểm thử cho DomainException và mã lỗi điều kiện thuê Task 6
     */
    private static Stream<Arguments> domainFailureCases() {
        return Stream.of(
                Arguments.of("search-rental-type", 422, "SEARCH_RENTAL_TYPE_NOT_SUPPORTED",
                        "Monthly rental search is not supported yet."),
                Arguments.of("search-drive-mode", 422, "SEARCH_DRIVE_MODE_NOT_SUPPORTED",
                        "Search with a driver is not supported yet."),
                Arguments.of("search-pickup", 422, "SEARCH_PICKUP_METHOD_NOT_SUPPORTED",
                        "Search with vehicle delivery is not supported yet."),
                Arguments.of("search-sort", 422, "SEARCH_SORT_NOT_SUPPORTED",
                        "Only nearest-first search is currently supported."),
                Arguments.of(
                        "rental-duration", 422, "RENTAL_DURATION_TOO_SHORT",
                        "The rental duration is shorter than the required minimum."
                ),
                Arguments.of(
                        "branch-hours", 422, "OUTSIDE_BRANCH_HOURS",
                        "Pickup and return must be within branch opening hours."
                ),
                Arguments.of(
                        "booking-window", 422, "BOOKING_WINDOW_VIOLATION",
                        "Pickup is outside the allowed advance booking window."
                ),
                Arguments.of(
                        "invalid-input",
                        400,
                        "INVALID_REQUEST",
                        "A required field is missing."
                ),
                Arguments.of(
                        "not-found",
                        404,
                        "BRANCH_NOT_FOUND",
                        "Branch not found."
                ),
                Arguments.of(
                        "conflict",
                        409,
                        "VEHICLE_PLATE_ALREADY_EXISTS",
                        "A vehicle with this plate number already exists."
                ),
                Arguments.of(
                        "rule-violation",
                        422,
                        "VEHICLE_DOCUMENT_EXPIRED",
                        "Vehicle inspection or compulsory liability insurance has expired."
                )
        );
    }

    /**
     * Kiểm mỗi nhóm lỗi được chuyển thành đúng HTTP và giữ nguyên
     * mã lỗi cùng thông báo công khai.
     *
     * @param scenario tên tình huống mà controller thử nghiệm sẽ tạo
     * @param expectedStatus mã HTTP mong đợi
     * @param expectedCode mã lỗi mong đợi trong JSON
     * @param expectedMessage thông báo mong đợi trong JSON
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @MethodSource("domainFailureCases")
    void mapsDomainFailuresToHttp(
            String scenario,
            int expectedStatus,
            String expectedCode,
            String expectedMessage
    ) throws Exception {
        mockMvc.perform(
                        get("/test/errors/{scenario}", scenario)
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(errorResponse(
                        expectedStatus,
                        expectedCode,
                        expectedMessage
                ));
    }

    /**
     * Kiểm response thành công có đủ success, data và error,
     * trong đó error phải hiện diện với giá trị null.
     *
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @Test
    void returnsSuccessfulResponseWithNullError() throws Exception {
        mockMvc.perform(
                        post("/test/echo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name": "Sample"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(content().json("""
                        {
                          "success": true,
                          "data": {
                            "name": "Sample"
                          },
                          "error": null
                        }
                        """, JsonCompareMode.STRICT));
    }

    /**
     * Kiểm JSON sai cú pháp được Spring phát hiện và trả HTTP 400
     * theo cấu trúc ApiResponse.
     *
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(
                        post("/test/echo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{")
                )
                .andExpect(errorResponse(
                        400,
                        "INVALID_REQUEST",
                        "The request contains invalid or missing input."
                ));
    }

    /**
     * Kiểm sai HTTP method vẫn trả 405 và giữ header Allow
     * để phía gọi biết method nào được hỗ trợ.
     *
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @Test
    void preservesMethodNotAllowedStatusAndAllowHeader() throws Exception {
        mockMvc.perform(
                        get("/test/echo")
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(errorResponse(
                        405,
                        "INVALID_REQUEST",
                        "The request contains invalid or missing input."
                ))
                .andExpect(header().string(
                        HttpHeaders.ALLOW,
                        "POST"
                ));
    }

    /**
     * Kiểm content type không được hỗ trợ vẫn trả 415 và giữ
     * header Accept mô tả định dạng request được chấp nhận.
     *
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @Test
    void preservesUnsupportedMediaTypeStatusAndAcceptHeader() throws Exception {
        mockMvc.perform(
                        post("/test/echo")
                                .contentType(MediaType.TEXT_PLAIN)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("Sample")
                )
                .andExpect(errorResponse(
                        415,
                        "INVALID_REQUEST",
                        "The request contains invalid or missing input."
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE
                ));
    }

    /**
     * Kiểm lỗi ngoài dự kiến trả 500 với thông báo chung.
     *
     * <p>So sánh JSON đầy đủ bảo đảm thông báo nội bộ của exception
     * và các trường chi tiết không bị đưa vào response.
     *
     * @throws Exception nếu quá trình xử lý request giả lập gặp lỗi
     */
    @Test
    void hidesInternalDetailsForUnexpectedFailure() throws Exception {
        mockMvc.perform(
                        get("/test/errors/unexpected")
                                .accept(MediaType.APPLICATION_JSON)
                )
                .andExpect(errorResponse(
                        500,
                        "INTERNAL_ERROR",
                        "An unexpected error occurred."
                ));
    }

    /**
     * Tạo bộ kiểm tra HTTP, content type và toàn bộ cấu trúc JSON lỗi.
     *
     * <p>So sánh STRICT bắt được trường bị thiếu, trường thừa
     * và giá trị khác mong đợi; không phụ thuộc thứ tự các khóa JSON.
     *
     * @param expectedStatus mã HTTP mong đợi
     * @param expectedCode mã lỗi mẫu cần xuất hiện trong response
     * @param expectedMessage thông báo mẫu cần xuất hiện trong response
     * @return bộ kiểm tra dùng cho các response lỗi
     */
    private static ResultMatcher errorResponse(
            int expectedStatus,
            String expectedCode,
            String expectedMessage
    ) {
        String expectedJson = """
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "%s",
                    "message": "%s"
                  }
                }
                """.formatted(expectedCode, expectedMessage);

        return result -> {
            status().is(expectedStatus).match(result);
            content().contentType(MediaType.APPLICATION_JSON).match(result);
            content().json(
                    expectedJson,
                    JsonCompareMode.STRICT
            ).match(result);
        };
    }

    /**
     * Cung cấp các endpoint chỉ dành cho test bộ xử lý lỗi.
     *
     * <p>Controller được đăng ký trực tiếp với MockMvc và không
     * được đưa vào ứng dụng production.
     */
    @RestController
    static class TestController {

        /**
         * Chủ động tạo lỗi theo tên tình huống để kiểm handler tương ứng.
         *
         * @param scenario tên tình huống kiểm thử
         * @throws DomainException khi kiểm một nhóm lỗi có chủ đích
         * @throws IllegalStateException khi kiểm lỗi hệ thống
         *                               hoặc nhận tên tình huống không hợp lệ
         */
        @GetMapping("/test/errors/{scenario}")
        void fail(@PathVariable("scenario") String scenario) {
            throw switch (scenario) {
                case "search-rental-type" -> DomainException.ruleViolation(ErrorCode.SEARCH_RENTAL_TYPE_NOT_SUPPORTED);
                case "search-drive-mode" -> DomainException.ruleViolation(ErrorCode.SEARCH_DRIVE_MODE_NOT_SUPPORTED);
                case "search-pickup" -> DomainException.ruleViolation(ErrorCode.SEARCH_PICKUP_METHOD_NOT_SUPPORTED);
                case "search-sort" -> DomainException.ruleViolation(ErrorCode.SEARCH_SORT_NOT_SUPPORTED);
                case "rental-duration" -> DomainException.ruleViolation(ErrorCode.RENTAL_DURATION_TOO_SHORT);
                case "branch-hours" -> DomainException.ruleViolation(ErrorCode.OUTSIDE_BRANCH_HOURS);
                case "booking-window" -> DomainException.ruleViolation(ErrorCode.BOOKING_WINDOW_VIOLATION);
                case "invalid-input" -> DomainException.invalidInput(
                        "A required field is missing."
                );
                case "not-found" -> DomainException.notFound(
                        ErrorCode.BRANCH_NOT_FOUND
                );
                case "conflict" -> DomainException.conflict(
                        ErrorCode.VEHICLE_PLATE_ALREADY_EXISTS
                );
                case "rule-violation" -> DomainException.ruleViolation(
                        ErrorCode.VEHICLE_DOCUMENT_EXPIRED
                );
                case "unexpected" -> new IllegalStateException(
                        "Simulated internal detail that must not be exposed."
                );
                default -> new IllegalStateException(
                        "Unknown test scenario."
                );
            };
        }

        /**
         * Nhận và trả lại dữ liệu mẫu để kiểm JSON thành công,
         * lỗi đọc request và các ràng buộc HTTP của Spring MVC.
         *
         * @param request dữ liệu JSON đã được Spring chuyển thành record
         * @return response thành công chứa dữ liệu mẫu
         */
        @PostMapping(
                path = "/test/echo",
                consumes = MediaType.APPLICATION_JSON_VALUE
        )
        ApiResponse<EchoRequest> echo(@RequestBody EchoRequest request) {
            return ApiResponse.success(request);
        }
    }

    /**
     * Dữ liệu request tối thiểu phục vụ kiểm chuyển đổi JSON.
     *
     * @param name giá trị mẫu được trả lại trong response thành công
     */
    public record EchoRequest(String name) {
    }
}
