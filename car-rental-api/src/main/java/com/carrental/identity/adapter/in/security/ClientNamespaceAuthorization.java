package com.carrental.identity.adapter.in.security;

import com.carrental.identity.api.CallerRef;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** BR-120/801, api-guideline §2: namespace khách cần JWT đã xác minh và vai CUSTOMER đã ánh xạ. */
@Component("clientNamespaceAuthorization")
class ClientNamespaceAuthorization implements AuthorizationManager<RequestAuthorizationContext> {
    /** Không nhận vai từ scope, authorities chung hay header do người gọi tự khai. */
    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                            RequestAuthorizationContext context) {
        var authenticated = authentication.get();
        Object caller = context.getRequest().getAttribute(CallerRef.class.getName());
        return new AuthorizationDecision(authenticated != null && authenticated.isAuthenticated()
                && authenticated.getPrincipal() instanceof Jwt
                && caller instanceof CallerRef ref && ref.hasRole("CUSTOMER"));
    }
}
