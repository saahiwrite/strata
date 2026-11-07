package edu.neu.strata.order.chaos;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChaosStateTest {

    private final ChaosState state = new ChaosState(new SimpleMeterRegistry());

    @Test
    void zeroRateNeverFails() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(state.shouldFail()).isFalse();
        }
    }

    @Test
    void fullRateAlwaysFails() {
        state.setErrorRate(1.0);
        for (int i = 0; i < 1_000; i++) {
            assertThat(state.shouldFail()).isTrue();
        }
    }

    @Test
    void partialRateFailsRoughlyThatFraction() {
        state.setErrorRate(0.5);
        int failures = 0;
        for (int i = 0; i < 10_000; i++) {
            if (state.shouldFail()) {
                failures++;
            }
        }
        assertThat(failures).isBetween(4_000, 6_000);
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> state.setErrorRate(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.setLatencyMs(ChaosState.MAX_LATENCY_MS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
