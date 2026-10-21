package com.carrental.identity.adapter.in.security;

import com.carrental.identity.domain.AccountRole;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm biên claims BR-120/129/801/809, không gọi Keycloak/CSDL và không giả vờ kiểm chữ ký.
 * Jwt ở đây là đầu ra giả lập của decoder; kiểm mật mã thật thuộc KeycloakJwtConfigurationTest.
 */
class KeycloakAccountClaimsMapperTest {
    private static final Instant CONSENTED_AT = Instant.parse("2026-10-12T03:00:00Z");
    private final KeycloakAccountClaimsMapper mapper = new KeycloakAccountClaimsMapper();

    /** Giữ subject và bản consent cũ, chuẩn hoá cùng số VN về E.164 mà không tự đổi phiên bản. */
    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+84912345678"})
    void mapsCustomerIdentityAndOriginalConsent(String phone) {
        var claims = customerClaims();
        claims.put("phone_number", phone);
        var command = mapper.map(jwt(claims));
        assertEquals("test-subject", command.keycloakSubject());
        assertEquals("+84912345678", command.phoneNumber().value());
        assertEquals(Set.of(AccountRole.CUSTOMER), command.roles().values());
        assertEquals("local-test-v1", command.consent().version());
        assertEquals(CONSENTED_AT, command.consent().consentedAt());
    }

    /** Nhận đủ 12 tên chính xác, không biến khai báo vai giai đoạn sau thành vai nhân viên. */
    @ParameterizedTest
    @EnumSource(AccountRole.class)
    void recognizesEveryProjectRealmRole(AccountRole role) {
        var claims = customerClaims();
        claims.put("realm_access", Map.of("roles", List.of(role.name())));
        assertEquals(Set.of(role), mapper.map(jwt(claims)).roles().values());
    }

    /** BR-809 cho phép vừa là khách vừa là một nhân viên; tên lặp không thành vai nhân viên thứ hai. */
    @Test
    void acceptsCustomerAndOneEmployeeWithDuplicateRoleNames() {
        var claims = customerClaims();
        claims.put("realm_access", Map.of("roles", List.of("CUSTOMER", "BRANCH_STAFF", "BRANCH_STAFF")));
        assertEquals(Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF),
                mapper.map(jwt(claims)).roles().values());
    }

    /** Nhân viên chưa đăng ký thuê không bị buộc có consent và không được tự cấp CUSTOMER. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN"})
    void acceptsEmployeeWithoutCustomerConsent(AccountRole role) {
        var claims = employeeClaims();
        claims.put("realm_access", Map.of("roles", List.of(role.name())));
        var command = mapper.map(jwt(claims));
        assertEquals(Set.of(role), command.roles().values());
        assertNull(command.consent());
    }

    /** Vai lạ, sai hoa thường và quyền ở client/scope không được cộng vào realm roles của tài khoản. */
    @Test
    void ignoresSystemRolesAndOtherAuthoritySources() {
        var claims = employeeClaims();
        claims.put("realm_access", Map.of("roles", List.of("offline_access", "uma_authorization",
                "default-roles-car-rental", "admin", " ADMIN", "FUTURE_ROLE")));
        claims.put("resource_access", Map.of("car-rental-api", Map.of("roles", List.of("ADMIN"))));
        claims.put("scope", "ADMIN CUSTOMER");
        claims.put("roles", List.of("ADMIN"));
        claims.put("branchId", 999L);
        claims.put("accountCode", "TK-FORGED");
        assertTrue(mapper.map(jwt(claims)).roles().values().isEmpty());
    }

    /** Không có realm roles thì không có quyền; không tự thêm vai mặc định để request chạy được. */
    @Test
    void leavesMissingRolesEmpty() {
        for (Object value : Arrays.asList(null, Map.of(), Map.of("roles", List.of()))) {
            var claims = employeeClaims();
            if (value == null) claims.remove("realm_access");
            else claims.put("realm_access", value);
            assertTrue(mapper.map(jwt(claims)).roles().values().isEmpty());
        }
    }

    /** Subject không được thiếu, tự cắt khoảng trắng, đổi kiểu hoặc chứa ký tự điều khiển. */
    @ParameterizedTest
    @MethodSource("invalidSubjects")
    void rejectsInvalidSubject(Object value) {
        assertUnauthenticatedWithClaim("sub", value);
    }

    /** Không ép số/object thành số điện thoại; số bàn/quốc tế hoặc số không hợp lệ đều là 401. */
    @ParameterizedTest
    @MethodSource("invalidPhones")
    void rejectsInvalidPhone(Object value) {
        assertUnauthenticatedWithClaim("phone_number", value);
    }

    /** Cấu trúc role hỏng không được im lặng bỏ qua phần hỏng rồi tiếp tục với quyền ADMIN còn lại. */
    @ParameterizedTest
    @MethodSource("invalidRoleStructures")
    void rejectsMalformedRealmRoles(Object value) {
        assertUnauthenticatedWithClaim("realm_access", value);
    }

    /** BR-809 không cho chọn vai mạnh nhất hoặc vai đầu tiên trong hai vai nhân viên. */
    @Test
    void rejectsMultipleEmployeeRoles() {
        assertUnauthenticatedWithClaim("realm_access", Map.of("roles", List.of("ADMIN", "BRANCH_STAFF")));
    }

    /** Thiếu hoàn toàn consent của khách hoặc chỉ có một nửa ở bất kỳ vai nào vẫn trả 403 đúng mã. */
    @Test
    void preservesConsentRequiredForMissingEvidence() {
        var customer = customerClaims();
        customer.remove("terms_version");
        customer.remove("consented_at");
        assertConsentRequired(customer);
        for (var claims : List.of(customerClaims(), employeeClaims())) {
            claims.put("terms_version", "local-test-v1");
            claims.remove("consented_at");
            assertConsentRequired(claims);
            claims.remove("terms_version");
            claims.put("consented_at", CONSENTED_AT.toString());
            assertConsentRequired(claims);
            claims.put("terms_version", " ");
            assertConsentRequired(claims);
        }
    }

    /** Consent sai kiểu không bị ép thành chuỗi, kể cả nhân viên vốn không bắt buộc có consent. */
    @Test
    void rejectsWrongConsentClaimTypes() {
        for (String name : List.of("terms_version", "consented_at")) {
            for (Object value : List.of(123L, true, List.of("value"), Map.of("value", "text"), "text\nforged")) {
                for (var claims : List.of(customerClaims(), employeeClaims())) {
                    claims.put(name, value);
                    assertUnauthenticated(assertThrowsExactly(DomainException.class, () -> mapper.map(jwt(claims))));
                }
            }
        }
    }

    /** Timestamp phải theo hợp đồng SPI ISO instant, không nhận ngày trơn hoặc epoch được đoán đơn vị. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "2026-10-12", "1791774000", "not-an-instant", "2026-10-12T03:00:00"})
    void rejectsMalformedConsentTimestamp(String value) {
        assertUnauthenticatedWithClaim("consented_at", value);
    }

    /** Phiên bản rỗng là thiếu bằng chứng, không tự thay bằng phiên bản mới đang cấu hình. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsMissingConsentVersion(String value) {
        var claims = customerClaims();
        if (value == null) claims.remove("terms_version");
        else claims.put("terms_version", value);
        assertConsentRequired(claims);
    }

    /** Thiếu đầu ra decoder không tạo danh tính giả; lỗi parser không mang cause hoặc giá trị claim. */
    @Test
    void rejectsMissingJwtWithoutLeakingDetails() {
        assertUnauthenticated(assertThrowsExactly(DomainException.class, () -> mapper.map(null)));
        assertUnauthenticatedWithClaim("consented_at", "sensitive-consent-value");
        assertUnauthenticatedWithClaim("phone_number", "sensitive-phone-value");
    }

    /** Bộ subject thiếu/sai kiểu để kiểm parser không dùng getClaimAsString tự chuyển kiểu. */
    static Stream<Object> invalidSubjects() {
        return Stream.of(null, "", " ", " subject", "subject ", "subject\nforged", 123L, List.of("subject"));
    }

    /** Giá trị thiếu, sai kiểu, số bàn và số ngoài VN không phải danh tính di động hợp lệ. */
    static Stream<Object> invalidPhones() {
        return Stream.of(null, "", " ", 84912345678L, true, List.of("+84912345678"),
                "+12025550123", "02838221234", "0912345678\nforged");
    }

    /** JSON role phải là object chứa array các chuỗi không rỗng, không có phần tử null/số/object. */
    static Stream<Object> invalidRoleStructures() {
        return Stream.of("ADMIN", List.of("ADMIN"), Map.of("roles", "ADMIN"), Map.of("roles", 123),
                Map.of("roles", List.of("ADMIN", 123)), Map.of("roles", Arrays.asList("ADMIN", null)),
                Map.of("roles", List.of(Map.of("name", "ADMIN"))), Map.of("roles", List.of("")),
                Map.of("roles", List.of("ADMIN\nforged")));
    }

    /** Claims tương ứng mapper realm thật; token thử không chứa dữ liệu hay secret local. */
    private Map<String, Object> customerClaims() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "test-subject");
        claims.put("phone_number", "+84912345678");
        claims.put("realm_access", Map.of("roles", List.of("CUSTOMER")));
        claims.put("terms_version", "local-test-v1");
        claims.put("consented_at", CONSENTED_AT.toString());
        return claims;
    }

    /** Nhân viên chưa từng đồng ý điều khoản thuê có cả hai claim consent vắng mặt. */
    private Map<String, Object> employeeClaims() {
        var claims = customerClaims();
        claims.put("realm_access", Map.of("roles", List.of("BRANCH_STAFF")));
        claims.remove("terms_version");
        claims.remove("consented_at");
        return claims;
    }

    /** Dựng đối tượng claims cho unit test mapper, không đi qua decoder và không cấp quyền HTTP. */
    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-token-not-a-real-credential").header("alg", "RS256")
                .claims(values -> values.putAll(claims)).build();
    }

    /** Thay đúng một claim rồi yêu cầu lỗi 401 cụ thể, không xanh nhờ ClassCastException/null. */
    private void assertUnauthenticatedWithClaim(String name, Object value) {
        var claims = customerClaims();
        if (value == null) claims.remove(name);
        else claims.put(name, value);
        assertUnauthenticated(assertThrowsExactly(DomainException.class, () -> mapper.map(jwt(claims))));
    }

    /** Thông điệp chung không phản chiếu nội dung token; không giữ cause có thể lộ dữ liệu trong log. */
    private void assertUnauthenticated(DomainException failure) {
        assertEquals(ErrorCode.UNAUTHENTICATED, failure.errorCode());
        assertEquals(DomainException.Category.UNAUTHENTICATED, failure.category());
        assertEquals(ErrorCode.UNAUTHENTICATED.defaultMessage(), failure.getMessage());
        assertNull(failure.getCause());
    }

    /** Thiếu consent giữ nguyên ngữ nghĩa 403 của BR-120, không biến thành lỗi máy chủ hay lỗi body. */
    private void assertConsentRequired(Map<String, Object> claims) {
        var failure = assertThrowsExactly(DomainException.class, () -> mapper.map(jwt(claims)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, failure.errorCode());
        assertEquals(DomainException.Category.FORBIDDEN, failure.category());
        assertEquals(ErrorCode.CONSENT_REQUIRED.defaultMessage(), failure.getMessage());
        assertNull(failure.getCause());
    }
}
