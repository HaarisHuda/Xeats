package com.xeats.backend.catalog;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** A campus or other Xeats service location. */
public record FoodCourt(UUID id, String code, String name, String timeZone, boolean active) {
    public FoodCourt {
        Objects.requireNonNull(id, "id");
        requireText(code, "code");
        requireText(name, "name");
        requireText(timeZone, "timeZone");
        ZoneId.of(timeZone);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
    }
}
