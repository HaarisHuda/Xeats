package com.xeats.backend.identity;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(UUID id, String username, List<UserRole> roles, List<UUID> assignedCounterIds) {
}
