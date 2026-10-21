package com.carrental.identity.adapter.in.security;

import com.carrental.identity.api.CallerDirectory;
import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.in.GetCallerUseCase;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.query.GetCallerQuery;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.domain.AccountRole;
import com.carrental.shared.api.ApiResponse;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.logging.RequestIdFilter;
import com.carrental.shared.logging.LogEventCapture;
import ch.qos.logback.classic.spi.ThrowableProxy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm filter với mapper, CallerDirectory và xử lý lỗi MVC thật; cổng application được cô lập.
 * Authentication ở đây giả lập đầu ra của Spring Security để kiểm vòng đời, không kiểm chữ ký
 * hoặc tuyên bố namespace đã khoá. Test decoder thật đã có; không tắt Security trong app/test suite.
 */
class AuthenticatedCallerFilterTest {
    private static final Instant CONSENTED_AT = Instant.parse("2026-10-12T03:00:00Z");
    private final ResolveAccountUseCase accounts = mock(ResolveAccountUseCase.class);
    private final GetCallerUseCase callers = mock(GetCallerUseCase.class);
    private AnnotationConfigWebApplicationContext context;
    private AuthenticatedCallerFilter filter;
    private MockMvc mvc;

    /** Đăng ký đúng hai lớp package-private production, không quét nhầm controller/config khác trong test. */
    @BeforeEach
    void setUp() throws ClassNotFoundException {
        SecurityContextHolder.clearContext();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Class.forName("com.carrental.shared.error.ApiExceptionHandler"),
                Class.forName("com.carrental.identity.adapter.in.internal.HttpCallerDirectory"));
        context.register(MvcConfiguration.class);
        context.refresh();
        var resolver = context.getBean("handlerExceptionResolver", HandlerExceptionResolver.class);
        filter = new AuthenticatedCallerFilter(new KeycloakAccountClaimsMapper(), accounts, callers, resolver);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestIdFilter(), filter).build();
        when(accounts.resolve(any())).thenReturn(account("TK-ABC123"));
        when(callers.get(any())).thenReturn(new CallerRef("TK-ABC123", Set.of("CUSTOMER"), null));
    }

    /** Test sở hữu context giả lập nên tự dọn; production giao SecurityContext cho Spring Security. */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    /** Request đi qua mapper → đồng bộ → đọc caller → controller; chỉ giữ caller trong thời gian xử lý. */
    @Test
    void resolvesAccountBeforeCallerAndExposesCallerToMvc() throws Exception {
        authenticate(jwt("subject-a"));
        var result = mvc.perform(get("/test/identity/caller").header("X-Request-Id", "identity-request-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("TK-ABC123"))
                .andExpect(header().string("X-Request-Id", "identity-request-1")).andReturn();
        var order = inOrder(accounts, callers);
        order.verify(accounts).resolve(argThat(command -> command.keycloakSubject().equals("subject-a")
                && command.phoneNumber().value().equals("+84912345678")
                && command.roles().values().equals(Set.of(AccountRole.CUSTOMER))
                && command.consent().consentedAt().equals(CONSENTED_AT)));
        order.verify(callers).get(argThat(query -> query.accountCode().equals("TK-ABC123")
                && query.roles().values().equals(Set.of(AccountRole.CUSTOMER))));
        verifyNoMoreInteractions(accounts, callers);
        assertNull(result.getRequest().getAttribute(CallerRef.class.getName()));
        assertNull(result.getRequest().getSession(false));
    }

    /** Không token vẫn đi được đường public; filter không tự tạo account hoặc CallerRef. */
    @Test
    void passesAnonymousRequestWithoutResolvingAnAccount() throws Exception {
        mvc.perform(get("/test/identity/public")).andExpect(status().isOk());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        mvc.perform(get("/test/identity/public")).andExpect(status().isOk());
        mvc.perform(get("/test/identity/caller")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(accounts, callers);
    }

    /** Header, query và thuộc tính cũ không thay được danh tính từ biên xác thực. */
    @Test
    void discardsForgedOrStaleCallerData() throws Exception {
        var forged = new CallerRef("TK-FORGED", Set.of("ADMIN"), 999L);
        mvc.perform(get("/test/identity/caller").requestAttr(CallerRef.class.getName(), forged)
                        .header("X-Account-Code", "TK-FORGED").header("X-Role", "ADMIN")
                        .header("Authorization", "Bearer not-verified-by-this-filter")
                        .param("branchId", "999").param("roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(accounts, callers);
        authenticate(jwt("subject-a"));
        mvc.perform(get("/test/identity/caller").requestAttr(CallerRef.class.getName(), forged)
                        .header("X-Role", "ADMIN").param("accountCode", "TK-FORGED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("TK-ABC123"));
    }

    /** Authentication chưa xác thực hoặc principal không phải JWT không được đồng bộ tài khoản. */
    @Test
    void rejectsUnauthenticatedOrNonJwtAuthentication() throws Exception {
        var pending = new TestingAuthenticationToken(jwt("subject-a"), null);
        pending.setAuthenticated(false);
        var other = new TestingAuthenticationToken("not-a-jwt", null, "ROLE_ADMIN");
        for (Authentication authentication : List.of(pending, other)) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            mvc.perform(get("/test/identity/caller")).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(accounts, callers);
    }

    /** Claim hỏng phải dừng trước use case; không đẩy lỗi parser chứa dữ liệu nhạy cảm vào response. */
    @Test
    void rejectsMalformedClaimsBeforeApplicationCalls() throws Exception {
        authenticate(Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("subject-a").claim("phone_number", "private-invalid-number").build());
        mvc.perform(get("/test/identity/caller")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-invalid-number"))));
        verifyNoInteractions(accounts, callers);
    }

    /** Lỗi có chủ đích từ đồng bộ tài khoản giữ HTTP/mã đã duyệt, không gọi bước đọc phạm vi. */
    @ParameterizedTest
    @MethodSource("accountFailures")
    void preservesAccountDomainFailure(DomainException failure, int expectedStatus) throws Exception {
        authenticate(jwt("subject-a"));
        when(accounts.resolve(any())).thenThrow(failure);
        var result = mvc.perform(get("/test/identity/caller")).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(failure.errorCode().name())).andReturn();
        verifyNoInteractions(callers);
        assertNull(result.getRequest().getAttribute(CallerRef.class.getName()));
    }

    /** Vai lệch bản gán giữ nguyên 403, không dùng account đã đồng bộ như caller thiếu kiểm quyền. */
    @Test
    void preservesCallerScopeFailure() throws Exception {
        authenticate(jwt("subject-a"));
        when(callers.get(any())).thenThrow(DomainException.forbidden(ErrorCode.STAFF_ROLE_MISMATCH));
        mvc.perform(get("/test/identity/caller")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STAFF_ROLE_MISMATCH"));
    }

    /** Lỗi hạ tầng trước controller vẫn dùng advice thật: JSON chung, không có SQL/class/stack trace. */
    @Test
    void hidesInternalDetailsForUnexpectedIdentityFailure() throws Exception {
        authenticate(jwt("subject-a"));
        var failure = new IllegalStateException("SELECT secret FROM identity.account");
        when(accounts.resolve(any())).thenThrow(failure);
        try (var logs = new LogEventCapture("com.carrental.shared.error.ApiExceptionHandler")) {
            mvc.perform(get("/test/identity/caller?private=query-sentinel").header("X-Request-Id", "identity-failure-1")
                            .header("Authorization", "Bearer token-sentinel"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Request-Id", "identity-failure-1"))
                .andExpect(content().json("""
                        {"success":false,"data":null,"error":{"code":"INTERNAL_ERROR",
                        "message":"An unexpected error occurred."}}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
            assertEquals(1, logs.events().size());
            var event = logs.events().getFirst();
            assertTrue(event.getFormattedMessage().startsWith("Failure summary:"));
            assertEquals("identity-failure-1", event.getMDCPropertyMap().get("requestId"));
            assertEquals("GET /test/identity/caller", event.getMDCPropertyMap().get("api"));
            assertFalse(event.getFormattedMessage().contains("query-sentinel"));
            assertFalse(event.getFormattedMessage().contains("token-sentinel"));
            assertSame(failure, ((ThrowableProxy) event.getThrowableProxy()).getThrowable());
        }
        verifyNoInteractions(callers);
    }

    /** Cổng trả thiếu account/caller hoặc lệch mã/vai không được cấp một danh tính khác cho request. */
    @Test
    void rejectsInconsistentApplicationResults() throws Exception {
        authenticate(jwt("subject-a"));
        when(accounts.resolve(any())).thenReturn(null);
        mvc.perform(get("/test/identity/caller")).andExpect(status().isInternalServerError());
        verifyNoInteractions(callers);
        when(accounts.resolve(any())).thenReturn(account("TK-ABC123"));
        for (CallerRef caller : java.util.Arrays.asList(null,
                new CallerRef("TK-OTHER1", Set.of("CUSTOMER"), null),
                new CallerRef("TK-ABC123", Set.of("ADMIN"), null))) {
            when(callers.get(any())).thenReturn(caller);
            mvc.perform(get("/test/identity/caller")).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        }
    }

    /** Không bắt lại lỗi downstream: truyền đúng exception, vẫn xoá thuộc tính request trong finally. */
    @Test
    void cleansCallerWhenDownstreamThrowsWithoutHandlingItTwice() throws Exception {
        authenticate(jwt("subject-a"));
        var errors = mock(HandlerExceptionResolver.class);
        var isolated = new AuthenticatedCallerFilter(new KeycloakAccountClaimsMapper(), accounts, callers, errors);
        var request = new MockHttpServletRequest();
        var failure = new ServletException("Downstream failure.");
        FilterChain chain = (incoming, response) -> {
            assertNotNull(incoming.getAttribute(CallerRef.class.getName()));
            throw failure;
        };
        assertSame(failure, assertThrowsExactly(ServletException.class,
                () -> isolated.doFilter(request, new MockHttpServletResponse(), chain)));
        assertNull(request.getAttribute(CallerRef.class.getName()));
        verifyNoInteractions(errors);
    }

    /** Resolver không xử lý được lỗi thì phải ném lại, không trả response 200 rỗng hoặc đi tiếp. */
    @Test
    void propagatesFailureWhenResolverCannotHandleIt() {
        authenticate(jwt("subject-a"));
        var errors = mock(HandlerExceptionResolver.class);
        var isolated = new AuthenticatedCallerFilter(new KeycloakAccountClaimsMapper(), accounts, callers, errors);
        var failure = new IllegalStateException("Account unavailable.");
        when(accounts.resolve(any())).thenThrow(failure);
        var request = new MockHttpServletRequest();
        assertSame(failure, assertThrowsExactly(IllegalStateException.class,
                () -> isolated.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> fail("Chain must stop."))));
        assertNull(request.getAttribute(CallerRef.class.getName()));
    }

    /** Request sau trên cùng thread không tái dùng caller của request trước khi Security đã dọn context. */
    @Test
    void consecutiveRequestsDoNotReuseCaller() throws Exception {
        authenticate(jwt("subject-a"));
        mvc.perform(get("/test/identity/caller")).andExpect(status().isOk());
        SecurityContextHolder.clearContext();
        mvc.perform(get("/test/identity/caller")).andExpect(status().isUnauthorized());
        verify(accounts, times(1)).resolve(any());
        verify(callers, times(1)).get(any());
    }

    /** Hai request cùng ở trong bước đồng bộ vẫn có caller và requestId riêng; không giữ dữ liệu trên filter. */
    @Test
    void concurrentRequestsDoNotMixIdentities() throws Exception {
        var ready = new CountDownLatch(2);
        when(accounts.resolve(any())).thenAnswer(invocation -> {
            ResolveAccountCommand command = invocation.getArgument(0);
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return account(command.keycloakSubject().equals("subject-a") ? "TK-ABC123" : "TK-DEF456");
        });
        when(callers.get(any())).thenAnswer(invocation -> {
            GetCallerQuery query = invocation.getArgument(0);
            return new CallerRef(query.accountCode(), Set.of("CUSTOMER"), null);
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> concurrentRequest("subject-a", "TK-ABC123", "request-a"));
            var second = workers.submit(() -> concurrentRequest("subject-b", "TK-DEF456", "request-b"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Dữ liệu lỗi có chủ đích đã có mã trong backend-guideline, không thêm mã riêng cho filter. */
    static Stream<Arguments> accountFailures() {
        return Stream.of(
                Arguments.of(DomainException.forbidden(ErrorCode.CONSENT_REQUIRED), 403),
                Arguments.of(DomainException.forbidden(ErrorCode.ACCOUNT_IDENTITY_MISMATCH), 403),
                Arguments.of(DomainException.conflict(ErrorCode.ACCOUNT_IDENTITY_CONFLICT), 409));
    }

    /** Mỗi worker sở hữu Authentication riêng và dọn lại sau khi MockMvc kết thúc. */
    private Void concurrentRequest(String subject, String code, String requestId) throws Exception {
        try {
            authenticate(jwt(subject));
            var result = mvc.perform(get("/test/identity/caller").header("X-Request-Id", requestId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(code))
                    .andExpect(header().string("X-Request-Id", requestId)).andReturn();
            assertNull(result.getRequest().getAttribute(CallerRef.class.getName()));
            return null;
        } finally { SecurityContextHolder.clearContext(); }
    }

    /** Chỉ giả lập kết quả xác thực cho unit test filter, không phải JWT hợp lệ gửi vào backend. */
    private void authenticate(Jwt jwt) {
        var authentication = new TestingAuthenticationToken(jwt, null, "unused-authority");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Claims giả đúng mapper realm; quyền nghiệp vụ không lấy từ authorities của TestingAuthenticationToken. */
    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token-not-a-real-credential").header("alg", "RS256").subject(subject)
                .claim("phone_number", "+84912345678").claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .claim("terms_version", "local-test-v1").claim("consented_at", CONSENTED_AT.toString()).build();
    }

    /** View giả lập đã được use case ghi/đọc lại; không kiểm CSDL trong bộ test này. */
    private AccountDetail account(String code) {
        return new AccountDetail(code, "+84912345678", "local-test-v1", CONSENTED_AT);
    }

    /** Chỉ khai báo MVC/controller trong test, không tạo endpoint bỏ qua Security ở main. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
        /** Controller dùng CallerDirectory production để xác nhận request attribute được đọc đúng. */
        @Bean
        TestController testController(CallerDirectory callers) { return new TestController(callers); }
    }

    /** Điểm cuối thử nghiệm, không trả số điện thoại hoặc token. */
    @TestComponent
    @RestController
    static class TestController {
        private final CallerDirectory callers;
        /** Nhận cổng đọc caller, không đọc trực tiếp SecurityContext hay header. */
        TestController(CallerDirectory callers) { this.callers = callers; }
        /** Trả mã của chính request; thiếu danh tính sẽ dùng advice chung trả 401. */
        @GetMapping("/test/identity/caller")
        ApiResponse<String> current() { return ApiResponse.success(callers.current().accountCode()); }
        /** Đường công khai chứng minh filter không tự chặn request ẩn danh hoặc tạo account. */
        @GetMapping("/test/identity/public")
        ApiResponse<String> publicResource() { return ApiResponse.success("public"); }
    }
}
