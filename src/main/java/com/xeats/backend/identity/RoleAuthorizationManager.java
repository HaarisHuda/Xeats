package com.xeats.backend.identity;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

@Component
public class RoleAuthorizationManager {
    private final IdentityRepository identities;

    public RoleAuthorizationManager(IdentityRepository identities) {
        this.identities = identities;
    }

    public AuthorizationManager<RequestAuthorizationContext> requireActiveUser() {
        return (authentication, context) -> new AuthorizationDecision(userId(authentication) != null
                && identities.isEnabled(userId(authentication)));
    }

    public AuthorizationManager<RequestAuthorizationContext> requireRole(UserRole role) {
        return (authentication, context) -> {
            UUID userId = userId(authentication);
            return new AuthorizationDecision(userId != null && identities.hasActiveRole(userId, role));
        };
    }

    public AuthorizationManager<RequestAuthorizationContext> requireAnyRole(UserRole... roles) {
        return (authentication, context) -> {
            UUID userId = userId(authentication);
            boolean allowed = userId != null
                    && java.util.Arrays.stream(roles).anyMatch(role -> identities.hasActiveRole(userId, role));
            return new AuthorizationDecision(allowed);
        };
    }

    private UUID userId(Supplier<? extends Authentication> authentication) {
        Object principal = authentication.get().getPrincipal();
        return principal instanceof XeatsPrincipal xeatsPrincipal ? xeatsPrincipal.userId() : null;
    }
}
