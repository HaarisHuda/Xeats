package com.xeats.backend.identity;

public record CsrfTokenResponse(String headerName, String token) {
}
