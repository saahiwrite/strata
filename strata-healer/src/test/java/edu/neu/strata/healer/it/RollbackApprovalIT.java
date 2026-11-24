package edu.neu.strata.healer.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import edu.neu.strata.healer.diagnosis.LokiLogClient;
import edu.neu.strata.healer.execution.ActionExecutor;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.verification.RecoveryVerifier;

/**
 * A rollback needs an engineer's approval (L2): the incident waits, the approve and deny calls decide it,
 * and a decision can only be answered once. Runs on real PostgreSQL; Loki, Docker and the verifier are faked.
 * Needs Docker for PostgreSQL; runs in mvn verify.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RollbackApprovalIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    LokiLogClient loki;

    @MockBean
    ActionExecutor executor;

    @MockBean
    RecoveryVerifier verifier;

    @Autowired
    TestRestTemplate http;

    @Test
    void approvedRollbackRunsOnceAndResolvesTheIncident() throws Exception {
        String container = "strata-order-service-approve";
        rollbackAlert(container);
        when(executor.execute(ActionType.ROLLBACK, container))
                .thenReturn(ExecutionResult.succeeded(ActionType.ROLLBACK, container));
        when(verifier.verify(anyString(), any())).thenReturn(true);

        Map<String, Object> waiting = waitForApproval(container);
        assertThat(waiting.get("action")).isEqualTo("ROLLBACK");
        assertThat(String.valueOf(waiting.get("preview"))).contains(container);
        assertThat(incidentOf(container).orElseThrow().get("status")).isEqualTo("OPEN");
        verify(executor, never()).execute(any(), eq(container));

        long decision = ((Number) waiting.get("decisionId")).longValue();
        assertThat(http.postForEntity("/api/approvals/" + decision + "/approve", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        Map<String, Object> closed = waitFor(() -> incidentOf(container).filter(i -> !"OPEN".equals(i.get("status"))));
        assertThat(closed.get("status")).isEqualTo("RESOLVED");
        verify(executor).execute(ActionType.ROLLBACK, container);
        assertThat(http.postForEntity("/api/approvals/" + decision + "/approve", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/approvals/" + decision + "/deny", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deniedRollbackNeverRunsAndGoesToAHuman() throws Exception {
        String container = "strata-inventory-service-deny";
        rollbackAlert(container);

        long decision = ((Number) waitForApproval(container).get("decisionId")).longValue();
        assertThat(http.postForEntity("/api/approvals/" + decision + "/deny", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(incidentOf(container).orElseThrow().get("status")).isEqualTo("ESCALATED");
        verify(executor, never()).execute(any(), eq(container));
        assertThat(http.postForEntity("/api/approvals/" + decision + "/approve", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/approvals/999999/approve", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForObject("/api/audit/verify", Map.class).get("valid")).isEqualTo(Boolean.TRUE);
    }

    private void rollbackAlert(String container) {
        when(loki.recentLogs(eq(container), any(), anyInt()))
                .thenReturn(List.of("INFO Started OrderServiceApplication in 4.2 seconds", "ERROR request failed with 500"));
        Map<String, Object> payload = Map.of("status", "firing", "alerts", List.of(Map.of(
                "status", "firing",
                "labels", Map.of("alertname", "HighErrorRate", "service", container.replace("strata-", ""),
                        "container", container, "severity", "critical"),
                "startsAt", Instant.now().toString())));
        assertThat(http.postForEntity("/api/alerts", payload, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    private Map<String, Object> waitForApproval(String container) throws InterruptedException {
        return waitFor(() -> http.exchange("/api/approvals", HttpMethod.GET, null,
                        new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody().stream()
                .filter(a -> container.equals(a.get("target")))
                .findFirst());
    }

    private Optional<Map<String, Object>> incidentOf(String container) {
        List<Map<String, Object>> incidents = http.exchange("/api/incidents", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() { }).getBody();
        return incidents.stream().filter(i -> container.equals(i.get("container"))).findFirst();
    }

    private static <T> T waitFor(Supplier<Optional<T>> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Optional<T> found = condition.get();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("nothing happened within 30 s");
    }
}
