package com.carrental.identity.adapter.in.security;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.port.in.GetCallerUseCase;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.query.GetCallerQuery;
import com.carrental.shared.error.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * BR-120/803/809, ADR-0006: nối JWT đã xác minh với tài khoản và phạm vi của đúng request.
 * Phải chạy sau bước xác thực bearer của Spring Security; không tự đọc/giải mã Authorization.
 * Chỉ đăng ký trong SecurityFilterChain; servlet registration bị tắt để tránh chạy hai lần.
 * Không thay hoặc giữ SecurityContext; vòng đời context do chuỗi Spring Security sở hữu.
 */
@Component("authenticatedCallerFilter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuthenticatedCallerFilter extends OncePerRequestFilter {
    private final KeycloakAccountClaimsMapper claims;
    private final ResolveAccountUseCase accounts;
    private final GetCallerUseCase callers;
    private final HandlerExceptionResolver errors;

    /**
     * Servlet container tạo filter sớm, kể cả registration bị tắt. Tiêm proxy tại các điểm này
     * để không kéo JDBC/Flyway hoặc MVC lên trước cấu hình datasource động của integration test.
     * Không đặt lazy trên service hoặc toàn ứng dụng; bean đích vẫn được khởi tạo theo vòng đời thường.
     * Proxy chỉ trì hoãn lấy dependency, không bỏ bước xác thực, đồng bộ hay kiểm phạm vi.
     */
    AuthenticatedCallerFilter(KeycloakAccountClaimsMapper claims, @Lazy ResolveAccountUseCase accounts,
                              @Lazy GetCallerUseCase callers,
                              @Lazy @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.claims = claims;
        this.accounts = accounts;
        this.callers = callers;
        this.errors = errors;
    }

    /**
     * Chỉ bắt lỗi của bước dựng danh tính; lỗi downstream để MVC/Security xử lý đúng một lần.
     * Request ẩn danh đi tiếp không có CallerRef; quyền namespace do SecurityFilterChain quyết định.
     * Luôn xoá thuộc tính danh tính, kể cả khi resolver hoặc downstream ném lỗi.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        request.removeAttribute(CallerRef.class.getName());
        try {
            try {
                prepareCaller(request);
            } catch (RuntimeException failure) {
                if (errors.resolveException(request, response, null, failure) == null) throw failure;
                return;
            }
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(CallerRef.class.getName());
        }
    }

    /**
     * Chỉ dùng principal JWT đã xác thực. Đồng bộ tài khoản xong mới gọi use case chỉ đọc phạm vi;
     * không giữ transaction hoặc dùng vai/chi nhánh do client khai trong header/body/query.
     */
    private void prepareCaller(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) return;
        if (!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw DomainException.unauthenticated();
        }
        var command = claims.map(jwt);
        var account = accounts.resolve(command);
        if (account == null) throw new IllegalStateException("Account resolution returned no account.");
        var caller = callers.get(GetCallerQuery.from(account.code(), command.roles()));
        var expectedRoles = command.roles().values().stream().map(Enum::name).collect(Collectors.toSet());
        if (caller == null || !account.code().equals(caller.accountCode())
                || !expectedRoles.equals(caller.roles())) {
            throw new IllegalStateException("Caller resolution returned an inconsistent identity.");
        }
        request.setAttribute(CallerRef.class.getName(), caller);
    }
}
