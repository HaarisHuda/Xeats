package com.xeats.backend.catalog;

import java.util.Objects;
import java.util.UUID;

/** A serving point that offers exactly one meal. */
public record Counter(
        UUID id,
        UUID vendorId,
        String name,
        String pickupInstructions,
        boolean active,
        boolean acceptingOnlineOrders) {

    public Counter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vendorId, "vendorId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        if (pickupInstructions == null || pickupInstructions.isBlank()) {
            throw new IllegalArgumentException("pickupInstructions must not be blank.");
        }
    }
}
