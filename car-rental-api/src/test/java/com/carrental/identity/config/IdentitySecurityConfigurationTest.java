package com.carrental.identity.config;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.in.GetCallerUseCase;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.ReadAccountRolesPort;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.application.query.GetCallerQuery;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.AccountRoles;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.TermsConsent;
import com.carrental.identity.domain.VietnamMobileNumber;
import com.carrental.shared.api.ApiResponse;
import com.carrental.shared.code.BusinessCodeGenerator;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.logging.RequestIdFilter;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import com.carrental.vehicle.domain.DailyRate;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleSpecifications;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Chuỗi Security và decoder production, JWT ký RSA thật, JWKS HTTP loopback; không mock xác thực.
 * Cô lập hai use case CSDL để kiểm HTTP/namespace; kiểm ràng buộc và tranh chấp có test riêng.
 * /accounts/me dùng controller, AccountQueryService và HttpCallerDirectory thật, chỉ giả cổng đọc dữ liệu.
 * Không @WithMockUser, không jwt() postprocessor và không tắt Security.
 */
class IdentitySecurityConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");
    private static RSAKey signingKey;
    private static RSAKey otherKey;
    private final ResolveAccountUseCase accounts = mock(ResolveAccountUseCase.class);
    private final GetCallerUseCase callers = mock(GetCallerUseCase.class);
    private final ReadAccountPort storedAccounts = mock(ReadAccountPort.class);
    private final BranchDirectory branches = mock(BranchDirectory.class);
    private final ReadAccountRolesPort accountRoles = mock(ReadAccountRolesPort.class);
    private final ReadStaffBranchAssignmentPort assignmentReads = mock(ReadStaffBranchAssignmentPort.class);
    private final WriteStaffBranchAssignmentPort assignmentWrites = mock(WriteStaffBranchAssignmentPort.class);
    private final ReadBranchPort branchReads = mock(ReadBranchPort.class);
    private final WriteBranchPort branchWrites = mock(WriteBranchPort.class);
    private final BusinessCodeGenerator branchCodes = mock(BusinessCodeGenerator.class);
    private final ReadVehiclePort vehicleReads = mock(ReadVehiclePort.class);
    private final WriteVehiclePort vehicleWrites = mock(WriteVehiclePort.class);
    private final AtomicInteger keyRequests = new AtomicInteger();
    private HttpServer keys;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private String issuer;

    /** Khoá riêng chỉ tồn tại trong bộ nhớ test, server chỉ công bố khoá public. */
    @BeforeAll
    static void generateKeys() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("security-test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("other-key").generate();
    }

    /** Dựng MVC + cấu hình thật, không scan rộng khiến fixture test trở thành endpoint main. */
    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        keys = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keys.createContext("/realms/security-test/protocol/openid-connect/certs", exchange -> {
            keyRequests.incrementAndGet();
            byte[] json = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (var output = exchange.getResponseBody()) { output.write(json); }
            finally { exchange.close(); }
        });
        keys.start();
        var settings = new KeycloakReaderSettings("http://127.0.0.1:" + keys.getAddress().getPort(),
                "security-test", "unused-reader", "unused-secret");
        issuer = settings.baseUrl() + "/realms/" + settings.realm();
        when(accounts.resolve(any())).thenAnswer(invocation -> {
            ResolveAccountCommand command = invocation.getArgument(0);
            return new AccountDetail("TK-ABC123", command.phoneNumber().value(), "local-test-v1", NOW.minusSeconds(300));
        });
        when(callers.get(any())).thenAnswer(invocation -> {
            GetCallerQuery query = invocation.getArgument(0);
            return new CallerRef(query.accountCode(), query.roles().values().stream()
                    .map(Enum::name).collect(Collectors.toSet()), null);
        });
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("jwt-test",
                Map.of("car-rental.identity.jwt.audience", "car-rental-api")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("readerSettings", settings);
            factory.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
            factory.registerSingleton("resolveAccountUseCase", accounts);
            factory.registerSingleton("getCallerUseCase", callers);
            factory.registerSingleton("readAccountPort", storedAccounts);
            factory.registerSingleton("branchDirectory", branches);
            factory.registerSingleton("readAccountRolesPort", accountRoles);
            factory.registerSingleton("readStaffBranchAssignmentPort", assignmentReads);
            factory.registerSingleton("writeStaffBranchAssignmentPort", assignmentWrites);
            factory.registerSingleton("readBranchPort", branchReads);
            factory.registerSingleton("writeBranchPort", branchWrites);
            factory.registerSingleton("businessCodeGenerator", branchCodes);
            factory.registerSingleton("readVehiclePort", vehicleReads);
            factory.registerSingleton("writeVehiclePort", vehicleWrites);
        });
        context.register(KeycloakJwtConfiguration.class, IdentitySecurityConfiguration.class, MvcConfiguration.class,
                Class.forName("com.carrental.branch.application.service.BranchCommandService"),
                Class.forName("com.carrental.branch.application.service.BranchQueryService"),
                Class.forName("com.carrental.branch.adapter.in.rest.admin.AdminBranchController"),
                Class.forName("com.carrental.vehicle.application.service.VehicleCommandService"),
                Class.forName("com.carrental.vehicle.application.service.VehicleQueryService"),
                Class.forName("com.carrental.vehicle.adapter.in.rest.admin.AdminVehicleController"),
                Class.forName("com.carrental.shared.error.ApiExceptionHandler"),
                Class.forName("com.carrental.identity.adapter.in.internal.HttpCallerDirectory"),
                Class.forName("com.carrental.identity.application.service.AccountQueryService"),
                Class.forName("com.carrental.identity.application.service.StaffBranchAssignmentCommandService"),
                Class.forName("com.carrental.identity.adapter.in.rest.client.ClientAccountController"),
                Class.forName("com.carrental.identity.adapter.in.rest.admin.AdminAccountController"));
        for (String type : List.of("KeycloakAccountClaimsMapper", "AuthenticatedCallerFilter",
                "ClientNamespaceAuthorization", "AdminNamespaceAuthorization", "ApiSecurityFailureHandler")) {
            context.register(Class.forName("com.carrental.identity.adapter.in.security." + type));
        }
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestIdFilter(),
                context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    /** Đóng client JWKS qua destroyMethod của bean, dừng server và dọn context test. */
    @AfterEach
    void tearDown() {
        try { if (context != null) context.close(); }
        finally {
            if (keys != null) keys.stop(0);
            SecurityContextHolder.clearContext();
        }
    }

    /** Public và health vẫn dùng được không token, không tạo account hoặc gọi JWKS. */
    @Test
    void permitsPublicAndHealthWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/public/probe")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        verifyNoInteractions(accounts, callers);
        assertEquals(0, keyRequests.get());
    }

    /** Mọi method của hai namespace riêng đều cần token, trả envelope và Bearer challenge thống nhất. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/client/probe", "/api/v1/admin/probe"})
    void rejectsMissingToken(String path) throws Exception {
        assertUnauthorized(get(path));
        assertUnauthorized(post(path));
        verifyNoInteractions(accounts, callers);
    }

    /** Token khách qua decoder thật được gọi cả GET và POST mà không cần CSRF/session. */
    @Test
    void acceptsCustomerJwtAndClearsSecurityContext() throws Exception {
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        for (var request : List.of(get("/api/v1/client/probe"), post("/api/v1/client/probe"))) {
            var result = mvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("TK-ABC123")).andReturn();
            assertNull(result.getRequest().getSession(false));
            assertNull(result.getRequest().getAttribute(CallerRef.class.getName()));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
        assertTrue(keyRequests.get() > 0);
        verify(accounts, times(2)).resolve(any());
        verify(callers, times(2)).get(any());
    }

    /** Khách hợp lệ vẫn không có quyền nhân viên. */
    @Test
    void rejectsCustomerFromAdminNamespace() throws Exception {
        assertForbidden("/api/v1/admin/probe", sign(claims("CUSTOMER", "customer-web").build(), signingKey));
    }

    /** Bảy vai nhân viên vào cửa admin bằng client MFA; quyền thao tác/chi nhánh được kiểm riêng. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN"})
    void acceptsEmployeeNamespaceOnlyWithEmployeeClient(AccountRole role) throws Exception {
        String token = sign(claims(role.name(), "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertForbidden("/api/v1/client/probe", token);
    }

    /** Vai ADMIN có chữ ký hợp lệ vẫn không đủ nếu token thiếu/sai client đã buộc mật khẩu + OTP. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"customer-web", "mobile", "identity-reader", "admin-web-other"})
    void rejectsEmployeeTokenFromOtherClient(String client) throws Exception {
        assertForbidden("/api/v1/admin/probe", sign(claims("ADMIN", client).build(), signingKey));
    }

    /** Vai GĐ2/GĐ3 không được coi là nhân viên hay tự cấp CUSTOMER. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"HOST", "BUSINESS_PARTNER", "BUSINESS_PARTNER_STAFF", "FREELANCE_DRIVER"})
    void rejectsPartnerRolesFromBothPrivateNamespaces(AccountRole role) throws Exception {
        String token = sign(claims(role.name(), "admin-web").build(), signingKey);
        assertForbidden("/api/v1/admin/probe", token);
        assertForbidden("/api/v1/client/probe", token);
    }

    /** Scope hoặc client roles tự mang ADMIN không thay thế realm roles đã ánh xạ. */
    @Test
    void ignoresScopesAndClientRolesWhenAuthorizing() throws Exception {
        var claims = claims("CUSTOMER", "customer-web").claim("scope", "ADMIN ROLE_ADMIN")
                .claim("resource_access", Map.of("car-rental-api", Map.of("roles", List.of("ADMIN"))));
        assertForbidden("/api/v1/admin/probe", sign(claims.build(), signingKey));
    }

    /** Sai chữ ký bị chặn trước cả hai use case, không chỉ kiểm claims đã giải mã. */
    @Test
    void rejectsWrongSignatureBeforeAccountResolution() throws Exception {
        assertUnauthorized(get("/api/v1/client/probe").header("Authorization", "Bearer "
                + sign(claims("CUSTOMER", "customer-web").build(), otherKey)));
        verifyNoInteractions(accounts, callers);
    }

    /** Cùng khóa ký nhưng sai issuer/audience/hạn vẫn bị chặn bởi decoder production. */
    @Test
    void rejectsInvalidIssuerAudienceAndExpiry() throws Exception {
        for (var invalid : List.of(claims("CUSTOMER", "customer-web").issuer("https://untrusted.invalid"),
                claims("CUSTOMER", "customer-web").audience("another-api"),
                claims("CUSTOMER", "customer-web").expirationTime(Date.from(NOW.minusSeconds(61))))) {
            assertUnauthorized(get("/api/v1/client/probe").header("Authorization", "Bearer " + sign(invalid.build(), signingKey)));
        }
        verifyNoInteractions(accounts, callers);
    }

    /** Token hỏng ở đường public cũng không được coi như danh tính ẩn danh hợp lệ. */
    @Test
    void rejectsMalformedTokenEvenOnPublicPath() throws Exception {
        assertUnauthorized(get("/api/v1/public/probe").header("Authorization", "Bearer invalid-token"));
        verifyNoInteractions(accounts, callers);
    }

    /** Query/form/cookie không phải nơi truyền bearer; không dùng Basic làm đường đăng nhập thay thế. */
    @Test
    void rejectsAlternativeCredentialTransport() throws Exception {
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        assertUnauthorized(get("/api/v1/client/probe").param("access_token", token));
        assertUnauthorized(post("/api/v1/client/probe").contentType("application/x-www-form-urlencoded")
                .param("access_token", token));
        assertUnauthorized(get("/api/v1/client/probe").header("Cookie", "access_token=" + token));
        assertUnauthorized(get("/api/v1/client/probe").header("Authorization", "Basic dXNlcjpwYXNz"));
        verifyNoInteractions(accounts, callers);
    }

    /** Không có phiên server: request kế tiếp không token phải 401 sau một request hợp lệ. */
    @Test
    void doesNotReuseAuthenticationBetweenRequests() throws Exception {
        mvc.perform(get("/api/v1/client/probe").header("Authorization", "Bearer "
                + sign(claims("CUSTOMER", "customer-web").build(), signingKey))).andExpect(status().isOk());
        assertUnauthorized(get("/api/v1/client/probe"));
        verify(accounts, times(1)).resolve(any());
    }

    /** Đường chưa khai báo bị chặn; chỉ ERROR dispatch nội bộ được phép giữ response lỗi gốc. */
    @Test
    void deniesUnknownPathsAndDirectErrorRequests() throws Exception {
        assertUnauthorized(get("/error"));
        assertForbidden("/api/v1/host/probe", sign(claims("ADMIN", "admin-web").build(), signingKey));
        mvc.perform(get("/error").with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; }))
                .andExpect(status().isOk());
    }

    /** Chỉ một filter dựng caller trong chuỗi và không bị Boot đăng ký thêm ngoài Security. */
    @Test
    void registersCallerOnlyInsideSecurityChain() {
        var registration = context.getBean("authenticatedCallerFilterRegistration", FilterRegistrationBean.class);
        assertFalse(registration.isEnabled());
        var filters = context.getBean("springSecurityFilterChain", FilterChainProxy.class)
                .getFilterChains().getFirst().getFilters();
        String callerName = "com.carrental.identity.adapter.in.security.AuthenticatedCallerFilter";
        assertEquals(1, filters.stream().filter(filter -> filter.getClass().getName().equals(callerName)).count());
        int bearer = -1;
        int caller = -1;
        int authorization = -1;
        for (int index = 0; index < filters.size(); index++) {
            String name = filters.get(index).getClass().getSimpleName();
            if (name.equals("BearerTokenAuthenticationFilter")) bearer = index;
            if (name.equals("AuthenticatedCallerFilter")) caller = index;
            if (name.equals("AuthorizationFilter")) authorization = index;
        }
        assertTrue(bearer >= 0 && caller > bearer && authorization > caller);
    }

    /** Controller thật trả đủ và chỉ các trường công khai; consent lấy từ bản lưu, không từ token mới. */
    @Test
    void readsCustomerAccountThroughRealControllerAndUseCase() throws Exception {
        stubStoredAccount(true);
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(get("/api/v1/client/accounts/me").header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "account-me-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "account-me-1"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"TK-ABC123","phoneNumber":"+84912345678",
                        "roles":["CUSTOMER"],"branchCode":null,"termsVersion":"original-local-v1",
                        "consentedAt":"2026-10-12T03:00:00Z"},"error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(storedAccounts).findByCode("TK-ABC123");
        verifyNoMoreInteractions(storedAccounts);
        verifyNoInteractions(branches);
    }

    /** Mọi vai nhân viên đọc được chính mình trước khi gán chi nhánh, không bị buộc consent khách. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER", "ADMIN"})
    void readsUnassignedEmployeeAccountWithoutConsent(AccountRole role) throws Exception {
        stubStoredAccount(false);
        var employeeClaims = claims(role.name(), "admin-web").claim("terms_version", null).claim("consented_at", null);
        String token = sign(employeeClaims.build(), signingKey);
        mvc.perform(get("/api/v1/admin/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"TK-ABC123","phoneNumber":"+84912345678",
                        "roles":["%s"],"branchCode":null,"termsVersion":null,"consentedAt":null},"error":null}
                        """.formatted(role.name()), org.springframework.test.json.JsonCompareMode.STRICT));
        verifyNoInteractions(branches);
    }

    /** Phạm vi đã gán chỉ xuất hiện dưới dạng mã, không có branchId hoặc subject trong JSON. */
    @Test
    void exposesAssignedBranchAsBusinessCodeOnly() throws Exception {
        stubStoredAccount(false);
        // Ghi đè answer mà không gọi answer cũ với giá trị null do matcher any() trả về.
        doReturn(new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), 42L)).when(callers).get(any());
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-LOCAL1")));
        String token = sign(claims("BRANCH_STAFF", "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"TK-ABC123","phoneNumber":"+84912345678",
                        "roles":["BRANCH_STAFF"],"branchCode":"CN-LOCAL1","termsVersion":null,
                        "consentedAt":null},"error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(branches).findById(42L);
    }

    /** Hai endpoint thật đều từ chối request thiếu hoặc sai chữ ký trước khi đọc tài khoản. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/client/accounts/me", "/api/v1/admin/accounts/me"})
    void rejectsUnauthenticatedAccountRequests(String path) throws Exception {
        assertUnauthorized(get(path));
        assertUnauthorized(get(path).header("Authorization", "Bearer "
                + sign(claims("ADMIN", "admin-web").build(), otherKey)));
        verifyNoInteractions(storedAccounts, branches);
    }

    /** Endpoint /me không phải ngoại lệ để dùng token khách đọc admin hoặc token nhân viên đọc client. */
    @Test
    void rejectsWrongNamespaceForCurrentAccount() throws Exception {
        assertForbidden("/api/v1/admin/accounts/me", sign(claims("CUSTOMER", "customer-web").build(), signingKey));
        assertForbidden("/api/v1/client/accounts/me", sign(claims("ADMIN", "admin-web").build(), signingKey));
        verifyNoInteractions(storedAccounts, branches);
    }

    /** Mã/số điện thoại/vai/chi nhánh tự khai không thay được danh tính của /me. */
    @Test
    void ignoresClientSuppliedAccountSelection() throws Exception {
        stubStoredAccount(true);
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(get("/api/v1/client/accounts/me").header("Authorization", "Bearer " + token)
                        .param("accountCode", "TK-OTHER1").param("phoneNumber", "+84987654321")
                        .header("X-Account-Code", "TK-OTHER1").header("X-Role", "ADMIN").header("X-Branch-Id", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("TK-ABC123"))
                .andExpect(jsonPath("$.data.phoneNumber").value("+84912345678"))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));
        verify(storedAccounts).findByCode("TK-ABC123");
        verifyNoMoreInteractions(storedAccounts);
        verifyNoInteractions(branches);
    }

    /** Lỗi bất ngờ từ use case trả 500 chung, không lộ SQL, lớp, số dòng hoặc số điện thoại. */
    @Test
    void hidesInternalDetailsWhenCurrentAccountReadFails() throws Exception {
        when(storedAccounts.findByCode("TK-ABC123"))
                .thenThrow(new IllegalStateException("SELECT secret FROM identity.account; +84912345678"));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(get("/api/v1/client/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"INTERNAL_ERROR",
                        "message":"An unexpected error occurred."}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    /** ADMIN qua controller và use case thật; vai đích đến từ cổng Keycloak, không từ token ADMIN. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF", "DRIVER"})
    void adminAssignsBranchThroughRealUseCase(AccountRole targetRole) throws Exception {
        stubAssignmentTarget(targetRole);
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"accountCode":"TK-STAFF1","branchCode":"CN-LOCAL1",
                        "staffRole":"%s","assignedAt":"2026-10-15T03:00:00Z"},"error":null}
                        """.formatted(targetRole.name()), org.springframework.test.json.JsonCompareMode.STRICT));
        verify(accountRoles).findBySubject("target-staff-subject");
        verify(assignmentWrites).save(new StaffBranchAssignment("TK-STAFF1", 42L, targetRole, NOW));
        verify(assignmentReads).findByAccountCode("TK-STAFF1");
    }

    /** Các vai nhân viên khác qua được namespace nhưng bị use case từ chối trước tra cứu đích/ghi. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void nonAdminCannotAssignBranch(AccountRole role) throws Exception {
        String token = sign(claims(role.name(), "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch")
                        .header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(storedAccounts, accountRoles, branches, assignmentWrites, assignmentReads);
    }

    /** Endpoint ghi vẫn yêu cầu JWT; khách không thể tự gán chi nhánh để thành nhân viên. */
    @Test
    void rejectsMissingTokenAndCustomerForBranchAssignment() throws Exception {
        assertUnauthorized(put("/api/v1/admin/accounts/TK-STAFF1/branch")
                .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(storedAccounts, accountRoles, branches, assignmentWrites, assignmentReads);
    }

    /** Body thiếu/null/trắng/sai JSON hoặc mã chi nhánh sai phải 400, không gọi cổng ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"branchCode\":null}", "{\"branchCode\":\" \"}",
            "{\"branchCode\":\"CN-invalid\"}", "{"})
    void rejectsInvalidAssignmentBody(String body) throws Exception {
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(storedAccounts, accountRoles, branches, assignmentWrites, assignmentReads);
    }

    /** Mã số hoặc mã TK sai trên URL không được dùng làm định danh gán chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"123", "TK-invalid"})
    void rejectsInvalidAssignmentAccountCode(String code) throws Exception {
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/" + code + "/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(storedAccounts, accountRoles, branches, assignmentWrites, assignmentReads);
    }

    /** Mã đúng dạng nhưng tài khoản chưa tồn tại trả 404, không tự tạo nhân viên. */
    @Test
    void returnsNotFoundForUnknownAssignmentAccount() throws Exception {
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        when(storedAccounts.findByCode("TK-STAFF1")).thenReturn(Optional.empty());
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        verifyNoInteractions(accountRoles, branches, assignmentWrites, assignmentReads);
    }

    /** Vai cấp công ty không được ép vào chi nhánh chỉ vì người gọi là ADMIN. */
    @Test
    void rejectsAssignmentToNonBranchScopedRole() throws Exception {
        stubAssignmentTarget(AccountRole.BRANCH_STAFF);
        when(accountRoles.findBySubject("target-staff-subject"))
                .thenReturn(Optional.of(new AccountRoles(Set.of(AccountRole.ACCOUNTANT))));
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("STAFF_ROLE_NOT_BRANCH_SCOPED"));
        verifyNoInteractions(branches, assignmentWrites, assignmentReads);
    }

    /** Xung đột quản lý giữ HTTP 409 và đúng mã; không retry rồi chiếm chỗ hoặc reload sau lỗi. */
    @Test
    void returnsConflictForSecondBranchManager() throws Exception {
        stubAssignmentTarget(AccountRole.BRANCH_MANAGER);
        when(assignmentWrites.save(any())).thenThrow(DomainException.conflict(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED));
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(put("/api/v1/admin/accounts/TK-STAFF1/branch").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"branchCode\":\"CN-LOCAL1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BRANCH_MANAGER_ALREADY_ASSIGNED"));
        verify(assignmentWrites, times(1)).save(any());
        verifyNoInteractions(assignmentReads);
    }

    /** BR-810: ADMIN qua JWT, controller và service thật; response chỉ trả mã nghiệp vụ. */
    @Test
    void adminCreatesBranchThroughRealUseCase() throws Exception {
        when(branchCodes.generate("CN")).thenReturn("CN-LOCAL1");
        when(branchWrites.insert(any())).thenReturn(true);
        when(branchReads.findByCode("CN-LOCAL1")).thenReturn(Optional.of(
                new BranchDetail(42L, "CN-LOCAL1", 10.76, 106.66, "Test Branch", "Local test address")));
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(createBranchRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/branches/CN-LOCAL1"))
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"CN-LOCAL1","latitude":10.76,"longitude":106.66,
                        "name":"Test Branch","address":"Local test address"},"error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        var order = inOrder(branchCodes, branchWrites, branchReads);
        order.verify(branchCodes).generate("CN");
        order.verify(branchWrites).insert(any());
        order.verify(branchReads).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(branchCodes, branchWrites, branchReads);
    }

    /** BR-810: sáu vai nhân viên khác qua namespace nhưng service chặn trước sinh mã/ghi/đọc. */
    @ParameterizedTest
    @EnumSource(value = AccountRole.class, names = {"BRANCH_STAFF", "BRANCH_MANAGER", "DELIVERY_STAFF",
            "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void employeeCannotCreateBranchByForgingAdminInput(AccountRole role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role.name()), role.isBranchScoped() ? 42L : null))
                .when(callers).get(any());
        String token = sign(claims(role.name(), "admin-web").build(), signingKey);
        mvc.perform(createBranchRequest().header("Authorization", "Bearer " + token)
                        .header("X-Role", "ADMIN").param("role", "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branchCodes, branchWrites, branchReads);
    }

    /** BR-810: đường tạo chi nhánh không mất chốt namespace khi thêm quyền trong use case. */
    @Test
    void branchCreationRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(createBranchRequest());
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(createBranchRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branchCodes, branchWrites, branchReads);
    }

    /** BR-810: JWT thật đi qua controller và service thật, ADMIN/nhân viên hợp lệ nhận mã, không ID. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void authorizedCallerReadsBranchThroughRealUseCase(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), "ADMIN".equals(role) ? null : 1000L))
                .when(callers).get(any());
        when(branchReads.findByCode("CN-LOCAL1")).thenReturn(Optional.of(
                new BranchDetail(1000L, "CN-LOCAL1", 10.76, 106.66, "Test Branch", "Local test address")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"CN-LOCAL1","latitude":10.76,"longitude":106.66,
                        "name":"Test Branch","address":"Local test address"},"error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(branchReads).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(branchReads);
        verifyNoInteractions(branchWrites, branchCodes);
    }

    /** Đổi mã URL, giả vai và chi nhánh trong header/query không vượt được phạm vi đã xác thực. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCannotReadAnotherBranchByForgingScope(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 2000L)).when(callers).get(any());
        when(branchReads.findByCode("CN-LOCAL1")).thenReturn(Optional.of(
                new BranchDetail(1000L, "CN-LOCAL1", 10.76, 106.66, "Other Branch", "Other address")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token)
                        .header("X-Role", "ADMIN").header("X-Branch-Id", "1000")
                        .param("branchId", "1000").param("role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"ACCESS_DENIED",
                        "message":"Access is denied."}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(branchReads).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(branchReads);
        verifyNoInteractions(branchWrites, branchCodes);
    }

    /** STAFF/MANAGER chưa gán được trả lỗi riêng trước khi truy vấn chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotReadBranch(String role) throws Exception {
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(branchReads, branchWrites, branchCodes);
    }

    /** Các vai nhân viên khác qua được namespace nhưng không được quyền đọc chi nhánh quản trị. */
    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotReadAdminBranch(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branchReads, branchWrites, branchCodes);
    }

    /** Cửa namespace vẫn chặn thiếu token và CUSTOMER, không chỉ dựa vào service. */
    @Test
    void branchReadRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(get("/api/v1/admin/branches/CN-LOCAL1"));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branchReads, branchWrites, branchCodes);
    }

    /** Người được phép đọc nhận đúng 404 khi mã không tồn tại, không đổi thành 403 hoặc 500. */
    @Test
    void adminReadMissingBranchReturnsNotFound() throws Exception {
        when(branchReads.findByCode("CN-LOCAL1")).thenReturn(Optional.empty());
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/branches/CN-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BRANCH_NOT_FOUND"));
        verify(branchReads).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(branchReads);
        verifyNoInteractions(branchWrites, branchCodes);
    }

    /** BR-810: đi qua JWT/controller/service thật, tạo COMPANY/DRAFT đúng giá và mã chi nhánh. */
    @ParameterizedTest
    @CsvSource({"ADMIN,false", "ADMIN,true", "BRANCH_STAFF,false", "BRANCH_MANAGER,false", "BRANCH_MANAGER,true"})
    void authorizedCallerCreatesVehicleThroughRealUseCase(String role, boolean priced) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), "ADMIN".equals(role) ? null : 1000L))
                .when(callers).get(any());
        when(branches.findByCode("CN-LOCAL1")).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        when(branchCodes.generate("XE")).thenReturn("XE-LOCAL1");
        when(vehicleWrites.insert(any())).thenReturn(true);
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(new VehicleDetail(
                77L, "XE-LOCAL1", "T08-VEHICLE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.DRAFT, null, null, 5, Transmission.AUTOMATIC, "Toyota", "Vios",
                null, priced ? 855000L : null)));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(createVehicleRequest(priced).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/admin/vehicles/XE-LOCAL1"))
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"XE-LOCAL1","plateNumber":"T08-VEHICLE-AUTH",
                        "ownershipType":"COMPANY","fuelType":"PETROL","branchCode":"CN-LOCAL1","status":"DRAFT",
                        "inspectionExpiresOn":null,"liabilityInsuranceExpiresOn":null,"seats":5,
                        "transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":%s},"error":null}
                        """.formatted(priced ? "855000" : "null"), org.springframework.test.json.JsonCompareMode.STRICT));
        var saved = org.mockito.ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleWrites).insert(saved.capture());
        assertEquals(1000L, saved.getValue().branchId());
        assertEquals(priced ? 855000L : null,
                saved.getValue().dailyRate() == null ? null : Long.valueOf(saved.getValue().dailyRate().amount()));
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleWrites, vehicleReads);
    }

    /** STAFF gửi giá bị chặn trước ghi; không thể giả vai MANAGER qua header/query. */
    @Test
    void staffCannotCreateVehicleWithDailyRate() throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of("BRANCH_STAFF"), 1000L)).when(callers).get(any());
        String token = sign(claims("BRANCH_STAFF", "admin-web").build(), signingKey);
        mvc.perform(createVehicleRequest(true).header("Authorization", "Bearer " + token)
                        .header("X-Role", "BRANCH_MANAGER").param("role", "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(branches, branchCodes, vehicleWrites, vehicleReads);
    }

    /** Đổi branchCode trong body không cho STAFF/MANAGER tạo ở chi nhánh khác. */
    @ParameterizedTest
    @CsvSource({"BRANCH_STAFF,false", "BRANCH_MANAGER,false", "BRANCH_MANAGER,true"})
    void employeeCannotCreateVehicleInAnotherBranch(String role, boolean priced) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 2000L)).when(callers).get(any());
        when(branches.findByCode("CN-LOCAL1")).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(createVehicleRequest(priced).header("Authorization", "Bearer " + token)
                        .header("X-Branch-Id", "1000").param("branchId", "1000"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verify(branches).findByCode("CN-LOCAL1");
        verifyNoMoreInteractions(branches);
        verifyNoInteractions(branchCodes, vehicleWrites, vehicleReads);
    }

    /** Chưa gán chi nhánh nhận 403 STAFF_BRANCH_REQUIRED, không dùng mã trong body để tự gán. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotCreateVehicle(String role) throws Exception {
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(createVehicleRequest(false).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"));
        verifyNoInteractions(branches, branchCodes, vehicleWrites, vehicleReads);
    }

    /** Các vai nhân viên còn lại không tự có quyền tạo xe khi qua được namespace admin. */
    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotCreateVehicle(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(createVehicleRequest(false).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branches, branchCodes, vehicleWrites, vehicleReads);
    }

    /** Không token nhận 401; CUSTOMER không được tạo xe qua namespace quản trị. */
    @Test
    void vehicleCreationRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(createVehicleRequest(false));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(createVehicleRequest(false).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(branches, branchCodes, vehicleWrites, vehicleReads);
    }

    /** BR-810: ADMIN/STAFF/MANAGER gửi xe hợp lệ qua service thật, giữ cả giá đã nhập trước đó. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void authorizedCallerSubmitsVehicleThroughRealUseCase(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), "ADMIN".equals(role) ? null : 1000L))
                .when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(submissionVehicle(VehicleStatus.DRAFT)));
        when(vehicleWrites.updateStatus("XE-LOCAL1", VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL))
                .thenReturn(true);
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(new VehicleDetail(
                77L, "XE-LOCAL1", "T08-SUBMIT-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.PENDING_APPROVAL, null, null, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, 855000L)));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":\"ACTIVE\",\"branchId\":2000,\"dailyRate\":1000}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"XE-LOCAL1","plateNumber":"T08-SUBMIT-AUTH",
                        "ownershipType":"COMPANY","fuelType":"PETROL","branchCode":"CN-LOCAL1",
                        "status":"PENDING_APPROVAL","inspectionExpiresOn":null,"liabilityInsuranceExpiresOn":null,
                        "seats":5,"transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":855000},
                        "error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleWrites).updateStatus("XE-LOCAL1", VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL);
        verifyNoMoreInteractions(vehicleWrites);
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verify(branches).findById(1000L);
        verifyNoMoreInteractions(branches);
        verifyNoInteractions(branchCodes);
    }

    /** Sai chi nhánh trả 403 trước kiểm trạng thái; body/header/query giả không thay phạm vi trên xe. */
    @ParameterizedTest
    @CsvSource({"BRANCH_STAFF,DRAFT", "BRANCH_MANAGER,DRAFT",
            "BRANCH_STAFF,PENDING_APPROVAL", "BRANCH_MANAGER,PENDING_APPROVAL"})
    void employeeCannotSubmitAnotherBranchVehicle(String role, VehicleStatus vehicleStatus) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 2000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(submissionVehicle(vehicleStatus)));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token)
                        .header("X-Role", "ADMIN").param("branchId", "2000")
                        .contentType("application/json").content("{\"branchId\":2000,\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").isEmpty());
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** STAFF/MANAGER chưa gán bị chặn trước khi tải xe, với đúng mã lỗi riêng. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotSubmitVehicle(String role) throws Exception {
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Các vai nhân viên còn lại không được gửi duyệt dù qua được namespace quản trị. */
    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotSubmitVehicle(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Không token trả 401; CUSTOMER không được gửi duyệt qua namespace admin. */
    @Test
    void vehicleSubmissionRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(submitVehicleRequest());
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Đủ quyền nhưng xe đã gửi duyệt vẫn nhận 422; quyền không bỏ qua máy trạng thái. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void authorizedResubmissionReturnsInvalidTransition(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1"))
                .thenReturn(Optional.of(submissionVehicle(VehicleStatus.PENDING_APPROVAL)));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VEHICLE_INVALID_STATUS_TRANSITION"));
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** Đủ quyền nhưng mã xe không tồn tại nhận 404, không đổi thành lỗi quyền hay gọi cổng ghi. */
    @Test
    void authorizedSubmissionMissingVehicleReturnsNotFound() throws Exception {
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.empty());
        mvc.perform(submitVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** BR-810: chỉ ADMIN/MANAGER hợp lệ đi qua JWT/controller/service thật để chuyển sang ACTIVE. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void authorizedCallerApprovesVehicleThroughRealUseCase(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), "ADMIN".equals(role) ? null : 1000L))
                .when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1"))
                .thenReturn(Optional.of(approvalVehicle(VehicleStatus.PENDING_APPROVAL)));
        when(vehicleWrites.updateStatus("XE-LOCAL1", VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE))
                .thenReturn(true);
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(new VehicleDetail(
                77L, "XE-LOCAL1", "T08-APPROVE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.ACTIVE, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16),
                5, Transmission.AUTOMATIC, "Toyota", "Vios", null, 855000L)));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"status\":\"RETIRED\",\"dailyRate\":1000,\"approvalDate\":\"2100-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"XE-LOCAL1","plateNumber":"T08-APPROVE-AUTH",
                        "ownershipType":"COMPANY","fuelType":"PETROL","branchCode":"CN-LOCAL1","status":"ACTIVE",
                        "inspectionExpiresOn":"2026-10-16","liabilityInsuranceExpiresOn":"2026-10-16",
                        "seats":5,"transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":855000},
                        "error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleWrites).updateStatus("XE-LOCAL1", VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE);
        verifyNoMoreInteractions(vehicleWrites);
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verify(branches).findById(1000L);
        verifyNoMoreInteractions(branches);
        verifyNoInteractions(branchCodes);
    }

    /** STAFF cùng chi nhánh và các vai nhân viên khác không được duyệt; giả vai không có tác dụng. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotApproveVehicle(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token)
                        .header("X-Role", "BRANCH_MANAGER").param("role", "ADMIN")
                        .contentType("application/json").content("{\"role\":\"ADMIN\",\"branchId\":1000}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** MANAGER khác chi nhánh bị chặn trước kiểm trạng thái và mọi thao tác ghi. */
    @ParameterizedTest
    @EnumSource(value = VehicleStatus.class, names = {"DRAFT", "PENDING_APPROVAL", "ACTIVE"})
    void managerCannotApproveAnotherBranchVehicle(VehicleStatus vehicleStatus) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of("BRANCH_MANAGER"), 2000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(approvalVehicle(vehicleStatus)));
        String token = sign(claims("BRANCH_MANAGER", "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token)
                        .header("X-Branch-Id", "1000").param("branchId", "1000"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** MANAGER chưa gán nhận STAFF_BRANCH_REQUIRED trước khi tải xe. */
    @Test
    void unassignedManagerCannotApproveVehicle() throws Exception {
        String token = sign(claims("BRANCH_MANAGER", "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Đủ quyền nhưng hồ sơ thiếu giấy tờ vẫn nhận 422, không ghi ACTIVE. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void authorizedApprovalStillRequiresDocuments(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1"))
                .thenReturn(Optional.of(submissionVehicle(VehicleStatus.PENDING_APPROVAL)));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VEHICLE_DOCUMENT_MISSING"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** Đủ quyền nhưng xe đã ACTIVE không được duyệt lại; giữ đúng lỗi trạng thái cũ. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void authorizedReapprovalReturnsInvalidTransition(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(approvalVehicle(VehicleStatus.ACTIVE)));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VEHICLE_INVALID_STATUS_TRANSITION"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** Cửa namespace giữ 401 khi thiếu token và 403 khi CUSTOMER gọi duyệt xe. */
    @Test
    void vehicleApprovalRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(approveVehicleRequest());
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** ADMIN gọi mã xe không tồn tại nhận 404, không thành lỗi quyền hay lỗi nội bộ. */
    @Test
    void authorizedApprovalMissingVehicleReturnsNotFound() throws Exception {
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.empty());
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(approveVehicleRequest().header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** Đọc qua JWT, controller và service thật; response chỉ trả mã chi nhánh, không ID nội bộ. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void authorizedCallerReadsVehicleThroughRealUseCase(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), role.equals("ADMIN") ? null : 1000L))
                .when(callers).get(any());
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(readableVehicle()));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "vehicle-read-allowed"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "vehicle-read-allowed"))
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"XE-LOCAL1","plateNumber":"T08-READ-AUTH",
                        "ownershipType":"COMPANY","fuelType":"PETROL","branchCode":"CN-LOCAL1",
                        "status":"DRAFT","inspectionExpiresOn":null,"liabilityInsuranceExpiresOn":"2020-01-01",
                        "seats":5,"transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":null},
                        "error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verify(branches).findById(1000L);
        verifyNoMoreInteractions(vehicleReads, branches);
        verifyNoInteractions(vehicleWrites, branchCodes);
    }

    /** Đổi URL và giả vai/phạm vi trong header/query không cho đọc dữ liệu xe khác chi nhánh. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void employeeCannotReadAnotherBranchVehicleByForgingScope(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 2000L)).when(callers).get(any());
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(readableVehicle()));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token)
                        .header("X-Role", "ADMIN").header("X-Branch-Id", "1000")
                        .param("branchId", "1000").param("role", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"ACCESS_DENIED","message":"Access is denied."}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verifyNoInteractions(branches, vehicleWrites, branchCodes);
    }

    /** STAFF/MANAGER chưa gán bị chặn trước I/O, không dùng branchCode client tự khai. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "BRANCH_MANAGER"})
    void unassignedEmployeeCannotReadVehicle(String role) throws Exception {
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token)
                        .param("branchCode", "CN-LOCAL1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Vai nhân viên khác dù qua namespace admin vẫn không có quyền GET danh mục xe. */
    @ParameterizedTest
    @ValueSource(strings = {"DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotReadVehicle(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Không token trả 401, CUSTOMER trả 403; không để GET thành đường xem xe không xác thực. */
    @Test
    void vehicleReadRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(get("/api/v1/admin/vehicles/XE-LOCAL1"));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Người đủ quyền nhưng mã không có trong kho nhận 404 VEHICLE_NOT_FOUND, không response dữ liệu rỗng. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_STAFF", "BRANCH_MANAGER"})
    void authorizedVehicleReadMissingCodeReturnsNotFound(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.empty());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(get("/api/v1/admin/vehicles/XE-LOCAL1").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** Read view DRAFT thiếu giá/giấy tờ để phân biệt quyền đọc với điều kiện phê duyệt. */
    private VehicleDetail readableVehicle() {
        return new VehicleDetail(5000L, "XE-LOCAL1", "T08-READ-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.DRAFT, null, LocalDate.of(2020, 1, 1),
                5, Transmission.AUTOMATIC, "Toyota", "Vios", null, null);
    }

    /** BR-810: ADMIN xuyên chi nhánh và MANAGER đúng chi nhánh cập nhật qua JWT/controller/service thật. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void authorizedCallerChangesDailyRateThroughRealUseCase(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), role.equals("ADMIN") ? 2000L : 1000L))
                .when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(approvalVehicle(VehicleStatus.DRAFT)));
        when(vehicleWrites.updateDailyRate("XE-LOCAL1", new DailyRate(900000))).thenReturn(true);
        var expiry = LocalDate.of(2026, 10, 16);
        when(vehicleReads.findByCode("XE-LOCAL1")).thenReturn(Optional.of(new VehicleDetail(
                5000L, "XE-LOCAL1", "T08-APPROVE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, VehicleStatus.DRAFT, expiry, expiry, 5, Transmission.AUTOMATIC,
                "Toyota", "Vios", null, 900000L)));
        when(branches.findById(1000L)).thenReturn(Optional.of(new BranchRef(1000L, "CN-LOCAL1")));
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("""
                        {"dailyRate":900000,"status":"ACTIVE","branchCode":"CN-FAKE01","role":"ADMIN"}
                        """).header("Authorization", "Bearer " + token).header("X-Request-Id", "rate-change-allowed"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "rate-change-allowed"))
                .andExpect(content().json("""
                        {"success":true,"data":{"code":"XE-LOCAL1","plateNumber":"T08-APPROVE-AUTH",
                        "ownershipType":"COMPANY","fuelType":"PETROL","branchCode":"CN-LOCAL1","status":"DRAFT",
                        "inspectionExpiresOn":"2026-10-16","liabilityInsuranceExpiresOn":"2026-10-16",
                        "seats":5,"transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":900000},
                        "error":null}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verify(vehicleWrites).updateDailyRate("XE-LOCAL1", new DailyRate(900000));
        verify(vehicleReads).findByCode("XE-LOCAL1");
        verify(branches).findById(1000L);
        verifyNoMoreInteractions(vehicleReads, vehicleWrites, branches);
        verifyNoInteractions(branchCodes);
    }

    /** STAFF cùng chi nhánh và các vai nhân viên khác đều nhận 403 trước tải/ghi xe. */
    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_STAFF", "DELIVERY_STAFF", "DRIVER", "ACCOUNTANT", "OPERATIONS_MANAGER"})
    void otherEmployeesCannotChangeDailyRate(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("{\"dailyRate\":900000}").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** MANAGER giả chi nhánh hoặc vai trên request không ghi được giá xe ngoài phạm vi đã xác thực. */
    @Test
    void managerCannotChangeAnotherBranchRateByForgingScope() throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of("BRANCH_MANAGER"), 2000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.of(approvalVehicle(VehicleStatus.ACTIVE)));
        String token = sign(claims("BRANCH_MANAGER", "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("{\"dailyRate\":900000,\"branchId\":1000,\"role\":\"ADMIN\"}")
                        .header("Authorization", "Bearer " + token).header("X-Role", "ADMIN")
                        .header("X-Branch-Id", "1000").param("branchId", "1000"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"ACCESS_DENIED","message":"Access is denied."}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        verify(vehicleReads).loadAggregate("XE-LOCAL1");
        verifyNoMoreInteractions(vehicleReads);
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** MANAGER chưa được gán nhận lỗi riêng, không dò xe hoặc cập nhật giá. */
    @Test
    void unassignedManagerCannotChangeDailyRate() throws Exception {
        String token = sign(claims("BRANCH_MANAGER", "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("{\"dailyRate\":900000}").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_BRANCH_REQUIRED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Người đủ quyền vẫn không nhập được giá thiếu/không dương/lẻ/vượt BIGINT; giữ validation BR-234. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"dailyRate\":null}", "{\"dailyRate\":0}", "{\"dailyRate\":-1000}",
            "{\"dailyRate\":1001}", "{\"dailyRate\":1000.5}", "{\"dailyRate\":9223372036854775808}"})
    void authorizedRateChangeStillRejectsInvalidInput(String body) throws Exception {
        String token = sign(claims("ADMIN", "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest(body).header("Authorization", "Bearer " + token))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("VEHICLE_INVALID_DAILY_RATE"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Không token nhận 401, CUSTOMER nhận 403; không có cửa đổi giá qua namespace sai. */
    @Test
    void dailyRateChangeRejectsMissingTokenAndCustomer() throws Exception {
        assertUnauthorized(changeDailyRateRequest("{\"dailyRate\":900000}"));
        String token = sign(claims("CUSTOMER", "customer-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("{\"dailyRate\":900000}").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(vehicleReads, vehicleWrites, branches, branchCodes);
    }

    /** Người đủ quyền đổi giá mã xe không tồn tại nhận 404, không tự tạo hoặc ghi dữ liệu khác. */
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "BRANCH_MANAGER"})
    void authorizedRateChangeMissingVehicleReturnsNotFound(String role) throws Exception {
        doReturn(new CallerRef("TK-ABC123", Set.of(role), 1000L)).when(callers).get(any());
        when(vehicleReads.loadAggregate("XE-LOCAL1")).thenReturn(Optional.empty());
        String token = sign(claims(role, "admin-web").build(), signingKey);
        mvc.perform(changeDailyRateRequest("{\"dailyRate\":900000}").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
        verifyNoInteractions(vehicleWrites, branches, branchCodes);
    }

    /** PUT thật chỉ ánh xạ dailyRate; các trường vai, chi nhánh hoặc trạng thái tự khai không vào command. */
    private MockHttpServletRequestBuilder changeDailyRateRequest(String body) {
        return put("/api/v1/admin/vehicles/XE-LOCAL1/daily-rate").contentType("application/json").content(body);
    }

    /** Endpoint production không nhận trạng thái hoặc ngày duyệt từ body. */
    private MockHttpServletRequestBuilder approveVehicleRequest() {
        return post("/api/v1/admin/vehicles/XE-LOCAL1/approve");
    }

    /** Giấy tờ còn hạn sau ngày đồng hồ cố định NOW của test, giá hợp lệ theo BR-234. */
    private Vehicle approvalVehicle(VehicleStatus status) {
        LocalDate expiry = LocalDate.of(2026, 10, 16);
        return Vehicle.restore("XE-LOCAL1", "T08-APPROVE-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, status, new VehicleDocuments(expiry, expiry),
                new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"), new DailyRate(855000));
    }

    /** Gọi đúng endpoint production, không cần body để chọn trạng thái hay phạm vi. */
    private MockHttpServletRequestBuilder submitVehicleRequest() {
        return post("/api/v1/admin/vehicles/XE-LOCAL1/submit-for-approval");
    }

    /** Xe có giá sẵn để chứng minh STAFF vẫn được gửi duyệt mà không có quyền nhập giá. */
    private Vehicle submissionVehicle(VehicleStatus status) {
        return Vehicle.restore("XE-LOCAL1", "T08-SUBMIT-AUTH", OwnershipType.COMPANY, FuelType.PETROL,
                1000L, status, new VehicleDocuments(null, null),
                new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"), new DailyRate(855000));
    }

    /** JSON hợp lệ; null là không nhập giá, không truyền trường vai/phạm vi giả vào command. */
    private MockHttpServletRequestBuilder createVehicleRequest(boolean priced) {
        return post("/api/v1/admin/vehicles").contentType("application/json").content("""
                {"plateNumber":"T08-VEHICLE-AUTH","fuelType":"PETROL","branchCode":"CN-LOCAL1",
                "seats":5,"transmission":"AUTOMATIC","make":"Toyota","model":"Vios","dailyRate":%s}
                """.formatted(priced ? "855000" : "null"));
    }

    /** Body hợp lệ để lỗi test quyền không bị lẫn với validation đầu vào. */
    private MockHttpServletRequestBuilder createBranchRequest() {
        return post("/api/v1/admin/branches").contentType("application/json").content("""
                {"latitude":10.76,"longitude":106.66,"name":"Test Branch","address":"Local test address"}
                """);
    }

    /** Dữ liệu các cổng đích; không mock use case để test vẫn chứng minh kiểm quyền ở application. */
    private void stubAssignmentTarget(AccountRole role) {
        when(storedAccounts.findByCode("TK-STAFF1")).thenReturn(Optional.of(Account.restore(
                "TK-STAFF1", "target-staff-subject", new VietnamMobileNumber("+84910000002"), null)));
        when(accountRoles.findBySubject("target-staff-subject")).thenReturn(Optional.of(new AccountRoles(Set.of(role))));
        when(branches.findByCode("CN-LOCAL1")).thenReturn(Optional.of(new BranchRef(42L, "CN-LOCAL1")));
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-LOCAL1")));
        when(assignmentWrites.save(any())).thenReturn(true);
        when(assignmentReads.findByAccountCode("TK-STAFF1"))
                .thenReturn(Optional.of(new StaffBranchAssignment("TK-STAFF1", 42L, role, NOW)));
    }

    /** Bản lưu giả ở cổng đọc; controller, AccountQueryService, HttpCallerDirectory và decoder đều chạy thật. */
    private void stubStoredAccount(boolean withConsent) {
        var consent = withConsent ? new TermsConsent("original-local-v1", Instant.parse("2026-10-12T03:00:00Z")) : null;
        when(storedAccounts.findByCode("TK-ABC123")).thenReturn(Optional.of(Account.restore(
                "TK-ABC123", "test-subject", new VietnamMobileNumber("+84912345678"), consent)));
    }

    /** Claims giả có đúng hợp đồng mapper Keycloak; chữ ký và luồng HTTP không được giả lập. */
    private JWTClaimsSet.Builder claims(String role, String client) {
        return new JWTClaimsSet.Builder().issuer(issuer).subject("test-subject").audience("car-rental-api")
                .issueTime(Date.from(NOW.minusSeconds(300))).expirationTime(Date.from(NOW.plusSeconds(300)))
                .claim("typ", "Bearer").claim("azp", client).claim("phone_number", "+84912345678")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .claim("terms_version", "local-test-v1").claim("consented_at", NOW.minusSeconds(300).toString());
    }

    /** Giữ kid công bố ngay cả khi ký bằng khóa sai để chứng minh decoder kiểm chữ ký, không chỉ kid. */
    private String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    /** 401 giữ response envelope, header Bearer và mã truy vết; không trả thông điệp decoder. */
    private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("X-Request-Id", "security-negative-1"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("X-Request-Id", "security-negative-1"))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Token hợp lệ nhưng sai quyền phải là 403 ACCESS_DENIED, không 401 hoặc 500. */
    private void assertForbidden(String path, String token) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Chỉ dựng controller thử nghiệm, không tạo cửa bỏ qua bảo mật trong main. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
        /** Cổng đọc caller thật để chuỗi kiểm cả việc dựng danh tính trước controller. */
        @Bean
        ProbeController probeController(CallerDirectory callers) { return new ProbeController(callers); }
    }

    /** Đường thử tách khỏi controller nghiệp vụ để phân biệt kiểm namespace với BR-810. */
    @TestComponent
    @RestController
    static class ProbeController {
        private final CallerDirectory callers;
        /** Không lấy thông tin người gọi từ header hoặc query trong controller. */
        ProbeController(CallerDirectory callers) { this.callers = callers; }
        /** Hai namespace riêng phải đi qua cùng xác thực và dựng caller. */
        @RequestMapping(path = {"/api/v1/client/probe", "/api/v1/admin/probe"}, method = {RequestMethod.GET, RequestMethod.POST})
        ApiResponse<String> privateResource() { return ApiResponse.success(callers.current().accountCode()); }
        /** Các đường công khai chỉ để chứng minh routing, không giả lập nghiệp vụ tìm xe. */
        @GetMapping({"/api/v1/public/probe", "/actuator/health", "/error"})
        ApiResponse<String> publicResource() { return ApiResponse.success("public"); }
    }
}
