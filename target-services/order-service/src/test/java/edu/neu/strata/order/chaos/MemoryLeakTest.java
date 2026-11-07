package edu.neu.strata.order.chaos;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryLeakTest {

    private static final long MB = 1024 * 1024;

    private final MemoryLeak leak = new MemoryLeak(new SimpleMeterRegistry());

    @Test
    void doesNothingWhileOff() {
        leak.tick();
        assertThat(leak.leakedBytes()).isZero();
        assertThat(leak.active()).isFalse();
    }

    @Test
    void growsEachTickUpToTheCap() {
        leak.start(4, 10);

        leak.tick();
        assertThat(leak.leakedBytes()).isEqualTo(4 * MB);
        leak.tick();
        assertThat(leak.leakedBytes()).isEqualTo(8 * MB);
        leak.tick();
        leak.tick();
        assertThat(leak.leakedBytes()).isEqualTo(10 * MB);
    }

    @Test
    void stopReleasesEverything() {
        leak.start(2, 10);
        leak.tick();
        leak.stop();

        assertThat(leak.leakedBytes()).isZero();
        assertThat(leak.active()).isFalse();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> leak.start(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leak.start(MemoryLeak.MAX_MB_PER_SECOND + 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leak.start(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
