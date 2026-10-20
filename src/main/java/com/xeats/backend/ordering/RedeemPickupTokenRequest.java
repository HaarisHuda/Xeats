package com.xeats.backend.ordering;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RedeemPickupTokenRequest(
        @NotBlank @Pattern(regexp = "^[0-9A-HJKMNP-TV-Z]{10}$") String code) {
    /** Keep unexpected fields from silently changing the redemption command contract. */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown redemption request property: " + name);
    }
}
