package com.xeats.backend.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,79}")
        String username,
        @NotBlank
        @Size(max = 256)
        String password) {
}
