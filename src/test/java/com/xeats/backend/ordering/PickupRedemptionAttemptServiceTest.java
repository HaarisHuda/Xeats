package com.xeats.backend.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PickupRedemptionAttemptServiceTest {
    @Test
    void limitsFailedScansByStaffAndCounterAndClearsAfterSuccessOrLockout() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T06:00:00Z"));
        PickupRedemptionAttemptService attempts = new PickupRedemptionAttemptService(clock);
        UUID staffId = UUID.randomUUID();
        UUID counterId = UUID.randomUUID();
        UUID otherCounterId = UUID.randomUUID();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(attempts.isAllowed(staffId, counterId)).isTrue();
            attempts.failed(staffId, counterId);
        }
        assertThat(attempts.isAllowed(staffId, counterId)).isFalse();
        assertThat(attempts.isAllowed(staffId, otherCounterId)).isTrue();
        assertThat(attempts.isAllowed(UUID.randomUUID(), counterId)).isTrue();

        clock.advance(Duration.ofMinutes(1).plusMillis(1));
        assertThat(attempts.isAllowed(staffId, counterId)).isTrue();
        attempts.failed(staffId, counterId);
        attempts.succeeded(staffId, counterId);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(attempts.isAllowed(staffId, counterId)).isTrue();
            attempts.failed(staffId, counterId);
        }
        assertThat(attempts.isAllowed(staffId, counterId)).isFalse();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
