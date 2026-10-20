package com.xeats.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {
    @Test
    void enforcesConfiguredLimitAndKeepsKeysIndependent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        XeatsSecurityProperties properties = new XeatsSecurityProperties(
                List.of("http://localhost:4200"), 2, Duration.ofMinutes(1), Duration.ofMinutes(1));
        LoginAttemptService attempts = new LoginAttemptService(clock, properties);

        assertThat(attempts.tryStart("known.user", "127.0.0.1")).isTrue();
        attempts.failed("known.user", "127.0.0.1");
        assertThat(attempts.tryStart("known.user", "127.0.0.1")).isTrue();
        attempts.failed("known.user", "127.0.0.1");
        assertThat(attempts.tryStart("known.user", "127.0.0.1")).isFalse();
        assertThat(attempts.tryStart("unknown.user", "127.0.0.1")).isTrue();

        clock.advance(Duration.ofMinutes(1).plusMillis(1));
        assertThat(attempts.tryStart("known.user", "127.0.0.1")).isTrue();
        attempts.succeeded("known.user", "127.0.0.1");
        assertThat(attempts.tryStart("known.user", "127.0.0.1")).isTrue();
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
