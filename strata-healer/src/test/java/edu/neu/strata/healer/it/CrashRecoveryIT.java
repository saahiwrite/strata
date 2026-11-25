package edu.neu.strata.healer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import edu.neu.strata.healer.verification.RecoveryVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The primary use case against real Docker and real PostgreSQL:
 * a container crashes, Alertmanager posts ServiceDown, and the healer restarts it,
 * marks the incident resolved and writes a valid audit chain.
 *
 * Only two things are faked: Loki isn't running (no logs, so the plain
 * service-down rule matches) and the recovery check looks at the container
 * instead of Prometheus. Needs Docker; runs in mvn verify.
 */
@Testcontainers
@AutoConfigureObservability  // tests turn metrics export off unless asked; this IT reads /actuator/prometheus
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrashRecoveryIT {

    // Matches strata-order-service-* in the policy allow-list.
    private static final String CONTAINER = "strata-order-service-it";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> orderService = new GenericContainer<>("alpine:3.20")
            .withCommand("sleep", "3600")
            .withCreateContainerCmdModifier(cmd -> cmd.withName(CONTAINER));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("strata.loki.url", () -> "http://localhost:1");
    }

    @MockBean
    RecoveryVerifier verifier;

    @Autowired
    TestRestTemplate http;

    private final DockerClient docker = DockerClientFactory.instance().client();

    @Test
    void crashedContainerIsRestartedAndIncidentResolved() throws Exception {
        when(verifier.verify(eq("order-service"), any(Duration.class))).thenAnswer(call -> isRunning());

        docker.killContainerCmd(CONTAINER).exec();
        assertFalse(isRunning());

        var response = http.postForEntity("/api/alerts", alertmanagerPayload(), Void.class);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        Map<String, Object> incident = waitForClosedIncident(Duration.ofSeconds(60));
        assertEquals("RESOLVED", incident.get("status"));
        assertTrue(isRunning(), "the healer should have restarted the container");

        Map<?, ?> verify = http.getForObject("/api/audit/verify", Map.class);
        assertEquals(Boolean.TRUE, verify.get("valid"));

        // The crash is on the healer's own metrics (#64): an incident, its time to recovery and an L0 decision
        assertEquals(1.0, metric("strata_incidents_closed_total", "status=\"RESOLVED\""), "resolved incident counted");
        assertEquals(1.0, metric("strata_incident_recovery_seconds_count"), "time to recovery recorded");
        assertEquals(1.0, metric("strata_decisions_total", "level=\"L0_AUTO\"", "source=\"RULE\""), "L0 decision counted");
        assertEquals(0.0, metric("strata_autonomy_level_shift", "action=\"RESTART\""), "autonomy gauge exposed");
    }

    /**
     * A value from {@code /actuator/prometheus}: the first sample of {@code name} whose labels include
     * every one of {@code labels}. Waits a little, since the counters move just after the incident closes.
     */
    private double metric(String name, String... labels) throws InterruptedException {
        String text = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            text = http.getForObject("/actuator/prometheus", String.class);
            for (String line : text.split("\n")) {
                if (!line.startsWith(name + "{") && !line.startsWith(name + " ")) {
                    continue;
                }
                String labelPart = line.contains("{") ? line.substring(line.indexOf('{'), line.lastIndexOf('}') + 1) : "";
                if (java.util.Arrays.stream(labels).allMatch(labelPart::contains)) {
                    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("no " + name + " " + String.join(",", labels) + " in:\n"
                + text.lines().filter(l -> l.startsWith("strata_")).collect(java.util.stream.Collectors.joining("\n")));
    }

    private boolean isRunning() {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(CONTAINER).exec().getState().getRunning());
    }

    private Map<String, Object> waitForClosedIncident(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Map<String, Object>> incidents = http.exchange("/api/incidents", HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody();
            if (incidents != null && !incidents.isEmpty() && !"OPEN".equals(incidents.get(0).get("status"))) {
                return incidents.get(0);
            }
            Thread.sleep(500);
        }
        throw new AssertionError("incident still open after " + timeout);
    }

    private static Map<String, Object> alertmanagerPayload() {
        return Map.of(
                "status", "firing",
                "alerts", List.of(Map.of(
                        "status", "firing",
                        "labels", Map.of(
                                "alertname", "ServiceDown",
                                "service", "order-service",
                                "container", CONTAINER,
                                "severity", "critical"),
                        "startsAt", Instant.now().toString())));
    }
}
