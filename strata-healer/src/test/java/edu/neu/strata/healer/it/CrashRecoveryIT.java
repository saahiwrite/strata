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
