package com.xeats.backend.catalog;

import java.util.Objects;
import java.util.UUID;

/** A vendor record scoped to one food court in the MVP. */
public record Vendor(UUID id, UUID foodCourtId, String name, VendorServiceModel serviceModel, boolean active) {
    public Vendor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(foodCourtId, "foodCourtId");
        Objects.requireNonNull(serviceModel, "serviceModel");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
    }
}
