package com.xeats.backend.identity;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "xeats.security")
public record XeatsSecurityProperties(
        List<String> allowedOrigins,
        int loginMaxAttempts,
        Duration loginWindow,
        Duration loginLockout) {
    public XeatsSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        loginWindow = loginWindow == null ? Duration.ofMinutes(1) : loginWindow;
        loginLockout = loginLockout == null ? Duration.ofMinutes(1) : loginLockout;
        if (loginMaxAttempts < 1) {
            throw new IllegalArgumentException("xeats.security.login-max-attempts must be positive.");
        }
        if (loginWindow.isZero() || loginWindow.isNegative()
                || loginLockout.isZero() || loginLockout.isNegative()) {
            throw new IllegalArgumentException("Login attempt windows and lockouts must be positive durations.");
        }
        if (allowedOrigins.stream().anyMatch(origin -> origin.isBlank() || origin.contains("*"))) {
            throw new IllegalArgumentException("Browser origins must be explicit; wildcard origins are not supported.");
        }
    }
}
