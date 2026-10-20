package com.xeats.backend.ordering;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(
        @NotNull UUID counterId,
        @NotNull UUID pickupSlotId) {

    /** Keep the HTTP payload aligned with the deliberately one-meal request contract. */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown order request property: " + name);
    }
}
