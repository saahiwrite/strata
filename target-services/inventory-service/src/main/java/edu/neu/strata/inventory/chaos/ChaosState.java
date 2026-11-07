package edu.neu.strata.inventory.chaos;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Current failure-switch settings. All fields are volatile so a switch flipped through
 * {@link ChaosController} takes effect on the next request without locking.
 */
@Component
public class ChaosState {

    public static final long MAX_LATENCY_MS = 60_000;

    private volatile double errorRate;
    private volatile long latencyMs;

    public ChaosState(MeterRegistry registry) {
        Gauge.builder("chaos_error_rate", this, ChaosState::errorRate)
                .description("Fraction of requests the error switch fails (0-1)")
                .register(registry);
        Gauge.builder("chaos_latency_ms", this, ChaosState::latencyMs)
                .description("Extra latency the slow switch adds to each request")
                .register(registry);
    }

    public double errorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        if (Double.isNaN(errorRate) || errorRate < 0 || errorRate > 1) {
            throw new IllegalArgumentException("rate must be between 0 and 1");
        }
        this.errorRate = errorRate;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        if (latencyMs < 0 || latencyMs > MAX_LATENCY_MS) {
            throw new IllegalArgumentException("delayMs must be between 0 and " + MAX_LATENCY_MS);
        }
        this.latencyMs = latencyMs;
    }

    /** Decides whether the current request should fail, based on the error rate. */
    public boolean shouldFail() {
        double rate = errorRate;
        return rate > 0 && (rate >= 1 || ThreadLocalRandom.current().nextDouble() < rate);
    }

    public void reset() {
        errorRate = 0;
        latencyMs = 0;
    }
}
