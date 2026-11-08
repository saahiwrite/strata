package edu.neu.strata.healer.verification;

import edu.neu.strata.healer.verification.PrometheusClient.Sample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides recovery from Prometheus (FR9). A service counts as healthy when
 * <ul>
 *   <li>every scrape target of the service is up ({@code up == 1}), and</li>
 *   <li>its 5xx share over the last minute is at or below {@code maxErrorRate}
 *       (the same query as the {@code HighErrorRate} alert; no traffic counts as fine).</li>
 * </ul>
 * It is recovered once it has been healthy for {@code requiredHealthyChecks} checks in a row,
 * so one lucky scrape right after a restart isn't enough. If Prometheus can't be reached the check
 * counts as not healthy; the verifier never throws for that, it just keeps trying until the timeout.
 */
public class PrometheusRecoveryVerifier implements RecoveryVerifier {

    /** Waits between checks. Swapped out in tests so they don't really sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(PrometheusRecoveryVerifier.class);

    // Service names go into PromQL, so only plain Compose-style names are accepted.
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private static final String APP_REQUESTS =
            "http_server_requests_seconds_count{service=\"%s\", uri!~\"/actuator.*|/chaos.*\"%s}";

    private final PrometheusClient prometheus;
    private final Duration pollInterval;
    private final int requiredHealthyChecks;
    private final double maxErrorRate;
    private final Clock clock;
    private final Sleeper sleeper;

    public PrometheusRecoveryVerifier(PrometheusClient prometheus, Duration pollInterval, int requiredHealthyChecks,
                                      double maxErrorRate) {
        this(prometheus, pollInterval, requiredHealthyChecks, maxErrorRate, Clock.systemUTC(),
                d -> Thread.sleep(d.toMillis()));
    }

    PrometheusRecoveryVerifier(PrometheusClient prometheus, Duration pollInterval, int requiredHealthyChecks,
                               double maxErrorRate, Clock clock, Sleeper sleeper) {
        this.prometheus = Objects.requireNonNull(prometheus, "prometheus");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, was " + pollInterval);
        }
        if (requiredHealthyChecks < 1) {
            throw new IllegalArgumentException("requiredHealthyChecks must be at least 1, was " + requiredHealthyChecks);
        }
        if (Double.isNaN(maxErrorRate) || maxErrorRate < 0.0 || maxErrorRate > 1.0) {
            throw new IllegalArgumentException("maxErrorRate must be between 0.0 and 1.0, was " + maxErrorRate);
        }
        this.requiredHealthyChecks = requiredHealthyChecks;
        this.maxErrorRate = maxErrorRate;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public boolean verify(String service, Duration timeout) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(timeout, "timeout");
        if (!SERVICE_NAME.matcher(service).matches()) {
            throw new IllegalArgumentException("not a valid service name: " + service);
        }
        Instant deadline = clock.instant().plus(timeout);
        int healthyInARow = 0;
        while (true) {
            healthyInARow = isHealthy(service) ? healthyInARow + 1 : 0;
            if (healthyInARow >= requiredHealthyChecks) {
                log.info("{} recovered ({} healthy checks in a row)", service, healthyInARow);
                return true;
            }
            if (clock.instant().plus(pollInterval).isAfter(deadline)) {
                log.warn("{} did not recover within {}", service, timeout);
                return false;
            }
            try {
                sleeper.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    boolean isHealthy(String service) {
        try {
            List<Sample> up = prometheus.query("up{service=\"" + service + "\"}");
            if (up.isEmpty() || up.stream().anyMatch(s -> s.value() != 1.0)) {
                log.debug("{} not up yet: {}", service, up);
                return false;
            }
            String errors = String.format(APP_REQUESTS, service, ", status=~\"5..\"");
            String all = String.format(APP_REQUESTS, service, "");
            List<Sample> errorRate = prometheus.query(
                    "sum(rate(" + errors + "[1m])) / sum(rate(" + all + "[1m]))");
            // Empty or NaN means there was no traffic in the last minute, so nothing is failing.
            boolean errorsOk = errorRate.stream()
                    .allMatch(s -> Double.isNaN(s.value()) || s.value() <= maxErrorRate);
            if (!errorsOk) {
                log.debug("{} error rate still too high: {}", service, errorRate);
            }
            return errorsOk;
        } catch (PrometheusException e) {
            log.warn("could not check {}: {}", service, e.getMessage());
            return false;
        }
    }
}
