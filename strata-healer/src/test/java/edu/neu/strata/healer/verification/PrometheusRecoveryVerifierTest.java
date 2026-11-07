package edu.neu.strata.healer.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.neu.strata.healer.verification.PrometheusClient.Sample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PrometheusRecoveryVerifierTest {

    private static final Duration POLL = Duration.ofSeconds(2);

    /** Answers `up` and error-rate queries from scripted values, one per check. */
    private static final class FakePrometheus extends PrometheusClient {
        final Deque<Object> up = new ArrayDeque<>();
        final Deque<Object> errorRate = new ArrayDeque<>();
        final List<String> queries = new ArrayList<>();

        FakePrometheus() {
            super(RestClient.create());
        }

        @Override
        public List<Sample> query(String promql) {
            queries.add(promql);
            Object next = promql.startsWith("up{") ? up.poll() : errorRate.poll();
            if (next instanceof PrometheusException e) {
                throw e;
            }
            if (next == null) {
                return List.of();
            }
            List<Sample> samples = new ArrayList<>();
            for (double v : (double[]) next) {
                samples.add(new Sample(Map.of(), v));
            }
            return samples;
        }

        FakePrometheus up(double... values) {
            up.add(values);
            return this;
        }

        FakePrometheus errors(double... values) {
            errorRate.add(values);
            return this;
        }
    }

    /** A clock that only moves when the verifier "sleeps". */
    private static final class FakeTime {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        int sleeps;

        Clock clock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return now; }
            };
        }

        void sleep(Duration d) {
            sleeps++;
            now = now.plus(d);
        }
    }

    private final FakeTime time = new FakeTime();

    private PrometheusRecoveryVerifier verifier(FakePrometheus prometheus) {
        return new PrometheusRecoveryVerifier(prometheus, POLL, 3, 0.2, time.clock(), time::sleep);
    }

    @Test
    void recoversAfterThreeHealthyChecksInARow() {
        FakePrometheus prometheus = new FakePrometheus()
                .up(1).errors(0.0)
                .up(1).errors(0.05)
                .up(1).errors(0.1);

        assertTrue(verifier(prometheus).verify("order-service", Duration.ofSeconds(30)));
        assertEquals(2, time.sleeps);
    }

    @Test
    void stillDownUntilTimeoutIsNotRecovered() {
        FakePrometheus prometheus = new FakePrometheus();
        for (int i = 0; i < 20; i++) {
            prometheus.up(0);
        }

        assertFalse(verifier(prometheus).verify("order-service", Duration.ofSeconds(10)));
        // checks at 0, 2, 4, 6, 8 and 10 seconds
        assertEquals(5, time.sleeps);
    }

    @Test
    void oneBadCheckResetsTheCount() {
        FakePrometheus prometheus = new FakePrometheus()
                .up(1).errors(0.0)
                .up(1).errors(0.0)
                .up(0)
                .up(1).errors(0.0)
                .up(1).errors(0.0)
                .up(1).errors(0.0);

        assertTrue(verifier(prometheus).verify("order-service", Duration.ofSeconds(30)));
        assertEquals(5, time.sleeps);
    }

    @Test
    void highErrorRateIsNotHealthy() {
        FakePrometheus prometheus = new FakePrometheus();
        for (int i = 0; i < 10; i++) {
            prometheus.up(1).errors(0.5);
        }

        assertFalse(verifier(prometheus).verify("order-service", Duration.ofSeconds(6)));
    }

    @Test
    void noTrafficCountsAsNoErrors() {
        FakePrometheus prometheus = new FakePrometheus()
                .up(1)                    // no error-rate series at all
                .up(1).errors(Double.NaN) // 0/0: no requests in the last minute
                .up(1).errors(0.2);       // exactly at the limit is fine

        assertTrue(verifier(prometheus).verify("order-service", Duration.ofSeconds(30)));
    }

    @Test
    void everyInstanceMustBeUp() {
        FakePrometheus prometheus = new FakePrometheus();
        for (int i = 0; i < 10; i++) {
            prometheus.up(1, 0).errors(0.0);
        }

        assertFalse(verifier(prometheus).verify("order-service", Duration.ofSeconds(6)));
    }

    @Test
    void noUpSeriesIsNotHealthy() {
        // The service isn't scraped at all (wrong name or Prometheus lost the target).
        assertFalse(verifier(new FakePrometheus()).verify("order-service", Duration.ofSeconds(4)));
    }

    @Test
    void prometheusErrorCountsAsNotHealthyAndKeepsTrying() {
        FakePrometheus prometheus = new FakePrometheus();
        prometheus.up.add(new PrometheusException("connection refused"));
        prometheus.up(1).errors(0.0).up(1).errors(0.0).up(1).errors(0.0);

        assertTrue(verifier(prometheus).verify("order-service", Duration.ofSeconds(30)));
    }

    @Test
    void queriesUseTheServiceLabelAndSkipActuatorAndChaos() {
        FakePrometheus prometheus = new FakePrometheus().up(1).errors(0.0);
        new PrometheusRecoveryVerifier(prometheus, POLL, 1, 0.2, time.clock(), time::sleep)
                .verify("inventory-service", Duration.ofSeconds(5));

        assertEquals("up{service=\"inventory-service\"}", prometheus.queries.get(0));
        String errorQuery = prometheus.queries.get(1);
        assertTrue(errorQuery.contains("service=\"inventory-service\""), errorQuery);
        assertTrue(errorQuery.contains("status=~\"5..\""), errorQuery);
        assertTrue(errorQuery.contains("uri!~\"/actuator.*|/chaos.*\""), errorQuery);
    }

    @Test
    void rejectsServiceNamesThatCouldChangeTheQuery() {
        PrometheusRecoveryVerifier verifier = verifier(new FakePrometheus());

        assertThrows(IllegalArgumentException.class, () -> verifier.verify("order\"} or vector(1) #", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify("", Duration.ofSeconds(5)));
        assertThrows(NullPointerException.class, () -> verifier.verify(null, Duration.ofSeconds(5)));
    }

    @Test
    void interruptStopsWaiting() {
        FakePrometheus prometheus = new FakePrometheus().up(0);
        PrometheusRecoveryVerifier verifier = new PrometheusRecoveryVerifier(prometheus, POLL, 3, 0.2, time.clock(),
                d -> { throw new InterruptedException(); });

        assertFalse(verifier.verify("order-service", Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted()); // flag restored (and cleared here for the next test)
    }

    @Test
    void rejectsBadSettings() {
        FakePrometheus prometheus = new FakePrometheus();

        assertThrows(IllegalArgumentException.class, () -> new PrometheusRecoveryVerifier(prometheus, Duration.ZERO, 3, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusRecoveryVerifier(prometheus, POLL, 0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusRecoveryVerifier(prometheus, POLL, 3, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new PrometheusRecoveryVerifier(prometheus, POLL, 3, Double.NaN));
    }
}
