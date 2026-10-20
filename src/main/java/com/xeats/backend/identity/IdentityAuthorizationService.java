package com.xeats.backend.identity;

import java.util.UUID;

import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class IdentityAuthorizationService {
    private final IdentityRepository identities;

    public IdentityAuthorizationService(IdentityRepository identities) {
        this.identities = identities;
    }

    public void requireCounterAssignment(XeatsPrincipal principal, UUID counterId) {
        if (!identities.hasCounterAssignment(principal.userId(), counterId)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "This action is not permitted.");
        }
    }

    public void requireCounterOperationAccess(XeatsPrincipal principal, UUID counterId) {
        if (!identities.hasActiveRole(principal.userId(), UserRole.DEMO_ADMIN)
                && !identities.hasCounterAssignment(principal.userId(), counterId)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "This action is not permitted.");
        }
    }

    public boolean isDemoAdmin(UUID userId) {
        return identities.hasActiveRole(userId, UserRole.DEMO_ADMIN);
    }

    public void requireOrderOwner(XeatsPrincipal principal, UUID orderOwnerId) {
        if (!principal.userId().equals(orderOwnerId)
                || !identities.hasActiveRole(principal.userId(), UserRole.EMPLOYEE)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                    "The requested resource was not found.");
        }
    }
}
