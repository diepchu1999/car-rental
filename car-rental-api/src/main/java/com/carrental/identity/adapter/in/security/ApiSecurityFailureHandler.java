package com.carrental.identity.adapter.in.security;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** ADR-0006: giữ response envelope chung cho lỗi Security, không đưa thông báo parser/token vào JSON. */
@Component("apiSecurityFailureHandler")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurityFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final HandlerExceptionResolver errors;

    /**
     * Dùng đúng resolver MVC có ApiExceptionHandler, không tự viết thêm bảng ánh xạ HTTP.
     * Proxy trì hoãn lấy resolver để lắp Security không kéo toàn bộ MVC lên quá sớm.
     */
    ApiSecurityFailureHandler(@Lazy @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.errors = errors;
    }

    /** Token thiếu/sai trả 401 và WWW-Authenticate: Bearer qua advice, không phản chiếu exception xác thực. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws ServletException {
        resolve(request, response, DomainException.unauthenticated());
    }

    /** Đã xác thực nhưng sai namespace/vai trả 403 ACCESS_DENIED, không chuyển thành 401 để đăng nhập lại. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
            throws ServletException {
        resolve(request, response, DomainException.forbidden(ErrorCode.ACCESS_DENIED));
    }

    /** Không im lặng trả 200 nếu cấu hình resolver thiếu advice; không kèm nguyên nhân chứa token thô. */
    private void resolve(HttpServletRequest request, HttpServletResponse response, DomainException failure)
            throws ServletException {
        if (errors.resolveException(request, response, null, failure) == null) {
            throw new ServletException("Security response could not be resolved.", failure);
        }
    }
}
