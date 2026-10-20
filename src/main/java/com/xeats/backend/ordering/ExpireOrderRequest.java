package com.xeats.backend.ordering;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExpireOrderRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    /** Keep unexpected fields from silently changing the expiry command contract. */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown expiry request property: " + name);
    }
}
