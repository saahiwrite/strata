package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CooldownGuardTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CooldownGuard guard = new CooldownGuard(Duration.ofSeconds(60), clock);

    @Test
    void newTargetIsNotCoolingDown() {
        assertFalse(guard.isCoolingDown("strata-order-service-1"));
    }

    @Test
    void targetCoolsDownRightAfterAnAction() {
        guard.markExecuted("strata-order-service-1");

        assertTrue(guard.isCoolingDown("strata-order-service-1"));
    }

    @Test
    void cooldownEndsAfterTheWindow() {
        guard.markExecuted("strata-order-service-1");
        clock.advance(Duration.ofSeconds(61));

        assertFalse(guard.isCoolingDown("strata-order-service-1"));
    }

    @Test
    void targetsAreTrackedSeparately() {
        guard.markExecuted("strata-order-service-1");

        assertFalse(guard.isCoolingDown("strata-inventory-service-1"));
    }
}
