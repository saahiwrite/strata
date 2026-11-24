package edu.neu.strata.chaos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Current failure-switch settings. All fields are volatile so a switch flipped through
 * {@link ChaosController} takes effect on the next request without locking.
 */
public class ChaosState {

    public static final long MAX_LATENCY_MS = 60_000;
    public static final int MAX_MESSAGE_LENGTH = 1_000;

    private volatile double errorRate;
    private volatile long latencyMs;
    private volatile String plantedMessage;
    private final Counter plantedLines;

    public ChaosState(MeterRegistry registry) {
        Gauge.builder("chaos_error_rate", this, ChaosState::errorRate)
                .description("Fraction of requests the error switch fails (0-1)")
                .register(registry);
        Gauge.builder("chaos_latency_ms", this, ChaosState::latencyMs)
                .description("Extra latency the slow switch adds to each request")
                .register(registry);
        this.plantedLines = Counter.builder("chaos_planted_log_lines")
                .description("Log lines written by the log switch")
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

    /**
     * The message the log switch planted, or {@code null}. While it is set, every request the
     * error switch fails logs this message instead of the generic one.
     */
    public String plantedMessage() {
        return plantedMessage;
    }

    public void setPlantedMessage(String message) {
        this.plantedMessage = checkMessage(message);
    }

    /** Checks a message for the log switch: not blank and at most {@link #MAX_MESSAGE_LENGTH} characters. */
    public static String checkMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        return message;
    }

    public void recordPlantedLine() {
        plantedLines.increment();
    }

    /** Decides whether the current request should fail, based on the error rate. */
    public boolean shouldFail() {
        double rate = errorRate;
        return rate > 0 && (rate >= 1 || ThreadLocalRandom.current().nextDouble() < rate);
    }

    public void reset() {
        errorRate = 0;
        latencyMs = 0;
        plantedMessage = null;
    }
}
