package com.carrental.shared.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm mười mã lỗi identity đã duyệt trong backend-guideline §5 qua handler MVC thật.
 * Controller chỉ tồn tại trong test; chưa chứng minh JWT hoặc phân quyền nghiệp vụ đã được bật.
 */
class IdentityErrorContractTest {
    private MockMvc mockMvc;

    /** Lắp riêng handler và controller thử, không truy cập CSDL và không đổi Security của ứng dụng. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new IdentityFailureController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** Mỗi mã phải giữ HTTP đã duyệt và envelope chính xác, không thêm định danh hoặc chi tiết nội bộ. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "UNAUTHENTICATED | 401 | Authentication is required.",
            "ACCESS_DENIED | 403 | Access is denied.",
            "STAFF_BRANCH_REQUIRED | 403 | A branch assignment is required for this employee.",
            "STAFF_ROLE_MISMATCH | 403 | The employee role does not match the branch assignment.",
            "ACCOUNT_IDENTITY_MISMATCH | 403 | The account identity does not match the authenticated identity.",
            "ACCOUNT_IDENTITY_CONFLICT | 409 | The authenticated identity conflicts with an existing account.",
            "ACCOUNT_NOT_FOUND | 404 | Account not found.",
            "BRANCH_MANAGER_ALREADY_ASSIGNED | 409 | The branch already has a different manager.",
            "STAFF_ROLE_NOT_BRANCH_SCOPED | 422 | The employee role is not branch-scoped.",
            "CONSENT_REQUIRED | 403 | Valid consent to the terms is required."
    })
    void preservesApprovedStatusAndEnvelope(String code, int expectedStatus, String message) throws Exception {
        var result = mockMvc.perform(get("/test/identity-errors/{code}", code))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"%s","message":"%s"}}
                        """.formatted(code, message), JsonCompareMode.STRICT));
        if (expectedStatus == 401) {
            result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        } else {
            result.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
        }
    }

    /** Lỗi token dùng thông báo chung, không trả nguyên nhân kiểm chữ ký hoặc dữ liệu token. */
    @Test
    void unauthenticatedFactoryUsesOnlyThePublicMessage() {
        DomainException failure = DomainException.unauthenticated();
        assertEquals(ErrorCode.UNAUTHENTICATED, failure.errorCode());
        assertEquals(DomainException.Category.UNAUTHENTICATED, failure.category());
        assertEquals("Authentication is required.", failure.getMessage());
        assertNull(failure.getCause());
    }

    /** Năm mã từ chối truy cập giữ đúng nhóm FORBIDDEN, không bị phân loại thành lỗi đầu vào 400. */
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ACCESS_DENIED", "STAFF_BRANCH_REQUIRED",
            "STAFF_ROLE_MISMATCH", "ACCOUNT_IDENTITY_MISMATCH", "CONSENT_REQUIRED"})
    void forbiddenFactoryPreservesTheApprovedCode(ErrorCode code) {
        DomainException failure = DomainException.forbidden(code);
        assertEquals(code, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        assertEquals(code.defaultMessage(), failure.getMessage());
        assertNull(failure.getCause());
    }

    /** Không cho phép tạo lỗi thiếu mã rồi âm thầm chọn mã mặc định. */
    @Test
    void rejectsMissingForbiddenCode() {
        assertThrowsExactly(NullPointerException.class, () -> DomainException.forbidden(null));
    }

    /** Điểm vào chỉ dành cho kiểm hợp đồng lỗi, không phải API hoặc cửa sau trong main. */
    @RestController
    static class IdentityFailureController {

        /** Chủ động ném từng lỗi đúng nhóm để kiểm HTTP/JSON qua handler thật. */
        @GetMapping("/test/identity-errors/{code}")
        void fail(@PathVariable("code") String code) {
            throw switch (ErrorCode.valueOf(code)) {
                case UNAUTHENTICATED -> DomainException.unauthenticated();
                case ACCESS_DENIED, STAFF_BRANCH_REQUIRED, STAFF_ROLE_MISMATCH,
                        ACCOUNT_IDENTITY_MISMATCH, CONSENT_REQUIRED -> DomainException.forbidden(ErrorCode.valueOf(code));
                case ACCOUNT_IDENTITY_CONFLICT, BRANCH_MANAGER_ALREADY_ASSIGNED -> DomainException.conflict(ErrorCode.valueOf(code));
                case ACCOUNT_NOT_FOUND -> DomainException.notFound(ErrorCode.ACCOUNT_NOT_FOUND);
                case STAFF_ROLE_NOT_BRANCH_SCOPED -> DomainException.ruleViolation(ErrorCode.STAFF_ROLE_NOT_BRANCH_SCOPED);
                default -> new IllegalArgumentException("Unsupported identity error scenario.");
            };
        }
    }
}
