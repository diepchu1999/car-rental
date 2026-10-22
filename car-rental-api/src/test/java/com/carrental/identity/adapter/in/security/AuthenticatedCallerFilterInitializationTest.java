package com.carrental.identity.adapter.in.security;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.in.GetCallerUseCase;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.shared.error.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ADR-0006, BR-120/803: tạo filter không được kéo use case CSDL hoặc MVC lên sớm.
 * Bean đích chỉ đặt lazy trong fixture để quan sát lúc dependency thật được lấy;
 * production không bật lazy toàn cục. Test Security riêng kiểm JWT ký thật và response envelope.
 */
class AuthenticatedCallerFilterInitializationTest {
    private final ResolveAccountUseCase accounts = mock(ResolveAccountUseCase.class);
    private final GetCallerUseCase callers = mock(GetCallerUseCase.class);
    private final HandlerExceptionResolver errors = mock(HandlerExceptionResolver.class);
    private final AtomicInteger accountCreations = new AtomicInteger();
    private final AtomicInteger callerCreations = new AtomicInteger();
    private final AtomicInteger resolverCreations = new AtomicInteger();
    private GenericWebApplicationContext context;

    /** Để Spring tiêm constructor thật; không tự new filter vì như vậy sẽ bỏ qua cơ chế proxy. */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.registerBean("accounts", ResolveAccountUseCase.class, () -> {
            accountCreations.incrementAndGet();
            return accounts;
        }, definition -> definition.setLazyInit(true));
        context.registerBean("callers", GetCallerUseCase.class, () -> {
            callerCreations.incrementAndGet();
            return callers;
        }, definition -> definition.setLazyInit(true));
        context.registerBean("handlerExceptionResolver", HandlerExceptionResolver.class, () -> {
            resolverCreations.incrementAndGet();
            return errors;
        }, definition -> definition.setLazyInit(true));
        new AnnotatedBeanDefinitionReader(context).register(KeycloakAccountClaimsMapper.class,
                AuthenticatedCallerFilter.class, ApiSecurityFailureHandler.class);
        context.refresh();
    }

    /** Không giữ Authentication hoặc context fixture sang test tiếp theo. */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    /** Bỏ @Lazy tại bất kỳ dependency đang kiểm nào sẽ khiến phép khẳng định này đỏ. */
    @Test
    void creatingSecurityAdaptersDoesNotInitializeApplicationOrMvcDependencies() {
        assertNotNull(context.getBean(AuthenticatedCallerFilter.class));
        assertNotNull(context.getBean(ApiSecurityFailureHandler.class));
        assertCreations(0, 0, 0);
    }

    /** Request ẩn danh không dùng proxy để tạo tài khoản, đọc phân công hoặc khởi tạo resolver. */
    @Test
    void anonymousRequestDoesNotResolveDeferredDependencies() throws Exception {
        var request = new MockHttpServletRequest();
        var chainCalls = new AtomicInteger();
        context.getBean(AuthenticatedCallerFilter.class).doFilter(request, new MockHttpServletResponse(),
                (incoming, response) -> chainCalls.incrementAndGet());
        assertEquals(1, chainCalls.get());
        assertCreations(0, 0, 0);
        verifyNoInteractions(accounts, callers, errors);
    }

    /** JWT đã xác thực vẫn gọi hai use case đúng thứ tự, lộ caller trong chain và dọn sau request. */
    @Test
    void authenticatedRequestResolvesApplicationDependenciesInOrder() throws Exception {
        authenticate();
        when(accounts.resolve(any())).thenReturn(new AccountDetail("TK-ABC123", "+84912345678",
                "local-test-v1", Instant.parse("2026-10-12T03:00:00Z")));
        var caller = new CallerRef("TK-ABC123", Set.of("CUSTOMER"), null);
        when(callers.get(any())).thenReturn(caller);
        assertCreations(0, 0, 0);
        var request = new MockHttpServletRequest();
        var chainCalls = new AtomicInteger();
        context.getBean(AuthenticatedCallerFilter.class).doFilter(request, new MockHttpServletResponse(),
                (incoming, response) -> {
                    chainCalls.incrementAndGet();
                    assertSame(caller, incoming.getAttribute(CallerRef.class.getName()));
                });
        assertEquals(1, chainCalls.get());
        assertCreations(1, 1, 0);
        var order = inOrder(accounts, callers);
        order.verify(accounts).resolve(any());
        order.verify(callers).get(any());
        verifyNoMoreInteractions(accounts, callers);
        verifyNoInteractions(errors);
        assertNull(request.getAttribute(CallerRef.class.getName()));
    }

    /** Lỗi đồng bộ vẫn tới resolver chung; không gọi use case đọc caller hay đi tiếp downstream. */
    @Test
    void accountFailureResolvesDeferredMvcHandlerAndStopsChain() throws Exception {
        authenticate();
        var failure = DomainException.unauthenticated();
        when(accounts.resolve(any())).thenThrow(failure);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        when(errors.resolveException(same(request), same(response), isNull(), same(failure)))
                .thenReturn(new ModelAndView());
        assertCreations(0, 0, 0);
        context.getBean(AuthenticatedCallerFilter.class).doFilter(request, response,
                (incoming, outgoing) -> fail("Chain must stop after account failure."));
        assertCreations(1, 0, 1);
        verify(errors).resolveException(same(request), same(response), isNull(), same(failure));
        verifyNoInteractions(callers);
        assertNull(request.getAttribute(CallerRef.class.getName()));
    }

    /** Đếm lúc Spring lấy bean đích, không nhầm với lúc gọi method trên mock đã tạo sẵn. */
    private void assertCreations(int expectedAccounts, int expectedCallers, int expectedResolver) {
        assertEquals(expectedAccounts, accountCreations.get(), "Account use case initialized too early.");
        assertEquals(expectedCallers, callerCreations.get(), "Caller use case initialized too early.");
        assertEquals(expectedResolver, resolverCreations.get(), "MVC resolver initialized too early.");
    }

    /** Giả lập đầu ra xác thực chỉ trong unit test vòng đời; không gửi token giả tới API thật. */
    private void authenticate() {
        var jwt = Jwt.withTokenValue("test-only-token").header("alg", "RS256").subject("subject-a")
                .claim("phone_number", "+84912345678")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .claim("terms_version", "local-test-v1")
                .claim("consented_at", "2026-10-12T03:00:00Z").build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null, "unused-authority"));
    }
}
