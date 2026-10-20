package com.xeats.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {
    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final Clock clock;
    private final XeatsSecurityProperties properties;

    public LoginAttemptService(Clock clock, XeatsSecurityProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public boolean tryStart(String username, String remoteAddress) {
        long now = clock.millis();
        String key = key(username, remoteAddress);
        AttemptWindow window = attempts.computeIfAbsent(key, ignored -> new AttemptWindow());
        boolean allowed = window.tryStart(now, properties.loginWindow().toMillis(),
                properties.loginLockout().toMillis(), properties.loginMaxAttempts());
        if (operations.incrementAndGet() % 256 == 0) {
            attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now,
                    properties.loginWindow().toMillis()));
        }
        return allowed;
    }

    public void failed(String username, String remoteAddress) {
        AttemptWindow window = attempts.get(key(username, remoteAddress));
        if (window != null) {
            window.failed(clock.millis(), properties.loginWindow().toMillis(), properties.loginLockout().toMillis(),
                    properties.loginMaxAttempts());
        }
    }

    public void succeeded(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
    }

    private String key(String username, String remoteAddress) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((username.toLowerCase(Locale.ROOT) + "\u0000" + remoteAddress)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static final class AttemptWindow {
        private final ArrayDeque<Long> startedAt = new ArrayDeque<>();
        private long blockedUntil;

        synchronized boolean tryStart(long now, long windowMillis, long lockoutMillis, int maxAttempts) {
            if (now < blockedUntil) {
                return false;
            }
            if (blockedUntil > 0) {
                blockedUntil = 0;
                startedAt.clear();
            }
            prune(now, windowMillis);
            if (startedAt.size() >= maxAttempts) {
                blockedUntil = now + lockoutMillis;
                return false;
            }
            startedAt.addLast(now);
            return true;
        }

        synchronized void failed(long now, long windowMillis, long lockoutMillis, int maxAttempts) {
            prune(now, windowMillis);
            if (startedAt.size() >= maxAttempts) {
                blockedUntil = now + lockoutMillis;
            }
        }

        synchronized boolean isExpired(long now, long windowMillis) {
            prune(now, windowMillis);
            return startedAt.isEmpty() && now >= blockedUntil;
        }

        private void prune(long now, long windowMillis) {
            long cutoff = now - windowMillis;
            while (!startedAt.isEmpty() && startedAt.getFirst() <= cutoff) {
                startedAt.removeFirst();
            }
        }
    }
}
