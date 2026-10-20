package com.xeats.backend.catalog;

import java.util.Objects;
import java.util.UUID;

/** The sole meal sold by one counter, priced in integer minor currency units. */
public record MenuItem(
        UUID id,
        UUID counterId,
        String name,
        String description,
        String category,
        long priceMinor,
        String currency,
        MenuItemAvailability availability) {

    public MenuItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(counterId, "counterId");
        Objects.requireNonNull(availability, "availability");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        if (priceMinor < 0) {
            throw new IllegalArgumentException("priceMinor must not be negative.");
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("Only INR is supported by the baseline demo.");
        }
    }
}
