package com.xeats.backend.ordering;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/** Single-instance throttle for failed token scans, scoped to one staff account and counter. */
@Service
public class PickupRedemptionAttemptService {
    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final long WINDOW_MILLIS = 60_000;
    private static final long LOCKOUT_MILLIS = 60_000;

    private final ConcurrentHashMap<AttemptKey, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final Clock clock;

    public PickupRedemptionAttemptService(Clock clock) {
        this.clock = clock;
    }

    public boolean isAllowed(UUID staffId, UUID counterId) {
        long now = clock.millis();
        AttemptKey key = new AttemptKey(staffId, counterId);
        AttemptWindow window = attempts.computeIfAbsent(key, ignored -> new AttemptWindow());
        boolean allowed = window.isAllowed(now);
        if (operations.incrementAndGet() % 256 == 0) {
            attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
        return allowed;
    }

    public void failed(UUID staffId, UUID counterId) {
        AttemptKey key = new AttemptKey(staffId, counterId);
        attempts.computeIfAbsent(key, ignored -> new AttemptWindow()).failed(clock.millis());
    }

    public void succeeded(UUID staffId, UUID counterId) {
        attempts.remove(new AttemptKey(staffId, counterId));
    }

    private record AttemptKey(UUID staffId, UUID counterId) { }

    private static final class AttemptWindow {
        private final ArrayDeque<Long> failedAt = new ArrayDeque<>();
        private long blockedUntil;

        synchronized boolean isAllowed(long now) {
            if (now < blockedUntil) {
                return false;
            }
            if (blockedUntil > 0) {
                blockedUntil = 0;
                failedAt.clear();
            }
            prune(now);
            return true;
        }

        synchronized void failed(long now) {
            if (now < blockedUntil) {
                return;
            }
            prune(now);
            failedAt.addLast(now);
            if (failedAt.size() >= MAX_FAILED_ATTEMPTS) {
                blockedUntil = now + LOCKOUT_MILLIS;
            }
        }

        synchronized boolean isExpired(long now) {
            prune(now);
            return failedAt.isEmpty() && now >= blockedUntil;
        }

        private void prune(long now) {
            long cutoff = now - WINDOW_MILLIS;
            while (!failedAt.isEmpty() && failedAt.getFirst() <= cutoff) {
                failedAt.removeFirst();
            }
        }
    }
}
