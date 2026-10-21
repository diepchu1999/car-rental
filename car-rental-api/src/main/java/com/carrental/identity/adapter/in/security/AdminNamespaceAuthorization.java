package com.carrental.identity.adapter.in.security;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.domain.AccountRole;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * BR-801/809: namespace nhân viên cần vai nhân viên và token của client admin-web.
 * Realm buộc client này qua mật khẩu + OTP, không có cookie/password grant hay service account.
 * azp đọc từ JWT đã kiểm chữ ký, không từ HTTP; token client khách không được nâng thành phiên nhân viên.
 * Đây chỉ là cửa namespace, không thay kiểm thao tác và chi nhánh BR-803/810 ở application.
 */
@Component("adminNamespaceAuthorization")
class AdminNamespaceAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
    /** Không coi ACCOUNTANT/OPERATIONS_MANAGER là ADMIN hoặc tự cấp quyền cho vai đối tác. */
    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                            RequestAuthorizationContext context) {
        var authenticated = authentication.get();
        Object caller = context.getRequest().getAttribute(CallerRef.class.getName());
        return new AuthorizationDecision(authenticated != null && authenticated.isAuthenticated()
                && authenticated.getPrincipal() instanceof Jwt jwt
                && "admin-web".equals(jwt.getClaims().get("azp"))
                && caller instanceof CallerRef ref
                && Arrays.stream(AccountRole.values()).filter(AccountRole::isEmployee)
                .anyMatch(role -> ref.hasRole(role.name())));
    }
}
