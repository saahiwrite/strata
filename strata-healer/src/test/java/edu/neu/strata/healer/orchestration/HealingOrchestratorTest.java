package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentService;
import edu.neu.strata.healer.diagnosis.LokiLogClient;
import edu.neu.strata.healer.diagnosis.RuleMatcher;
import edu.neu.strata.healer.execution.ActionExecutor;
import edu.neu.strata.healer.execution.CooldownGuard;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.DecisionStore;
import edu.neu.strata.healer.persistence.IncidentStore;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.policy.Policy;
import edu.neu.strata.healer.policy.PolicyLoader;
import edu.neu.strata.healer.policy.RiskScorer;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.validation.ActionValidator;
import edu.neu.strata.healer.verification.RecoveryVerifier;

class HealingOrchestratorTest {

    private static final String CONTAINER = "strata-order-service-1";

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);

    private final LokiLogClient loki = mock(LokiLogClient.class);
    private final ActionExecutor executor = mock(ActionExecutor.class);
    private final RecoveryVerifier verifier = mock(RecoveryVerifier.class);

    private IncidentService incidents;
    private AuditLogService audit;
    private TrustService trust;

    @BeforeEach
    void setUp() {
        incidents = new IncidentService(new IncidentStore(jdbc));
        audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
        trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, Clock.systemUTC());
        when(loki.recentLogs(anyString(), any(), anyInt())).thenReturn(List.of());
        when(executor.execute(any(), anyString()))
                .thenAnswer(call -> ExecutionResult.succeeded(call.getArgument(0), call.getArgument(1)));
        when(verifier.verify(anyString(), any())).thenReturn(true);
    }

    private HealingOrchestrator orchestrator(Policy policy) {
        return new HealingOrchestrator(incidents, loki, new RuleMatcher(), new ActionValidator(policy),
                new RiskScorer(policy), trust, new CooldownGuard(Duration.ofMinutes(5), Clock.systemUTC()),
                executor, verifier, audit, new DecisionStore(jdbc), Duration.ofSeconds(1), Runnable::run);
    }

    private HealingOrchestrator orchestrator() {
        return orchestrator(new PolicyLoader().loadDefault());
    }

    private Alert alert(String name, String container) {
        return new Alert(name, "order-service", container, "critical", Instant.parse("2026-11-07T10:00:00Z"));
    }

    private Incident open(Alert alert) {
        return incidents.open(alert);
    }

    private String incidentStatus(long id) {
        return jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(id).query(String.class).single();
    }

    private String decisionOutcome(long incidentId) {
        return jdbc.sql("SELECT outcome FROM decisions WHERE incident_id = ?").param(incidentId)
                .query(String.class).single();
    }

    @Test
    void crashHealsWithNoHumanInput() {
        Incident incident = open(alert("ServiceDown", CONTAINER));

        orchestrator().heal(incident);

        verify(executor).execute(ActionType.RESTART, CONTAINER);
        assertThat(incidentStatus(incident.id())).isEqualTo("RESOLVED");
        assertThat(decisionOutcome(incident.id())).isEqualTo("RECOVERED");
        assertThat(trust.get(ActionType.RESTART).successes()).isEqualTo(1);
        assertThat(trust.get(ActionType.RESTART).failures()).isZero();
    }

    @Test
    void healingWritesTheWholeStoryToTheAuditLog() {
        Incident incident = open(alert("ServiceDown", CONTAINER));

        orchestrator().heal(incident);

        List<AuditEventType> events = audit.forIncident(incident.id()).stream().map(AuditEntry::eventType).toList();
        assertThat(events).containsExactly(
                AuditEventType.INCIDENT_OPENED, AuditEventType.DIAGNOSIS, AuditEventType.VALIDATION,
                AuditEventType.RISK_ASSESSMENT, AuditEventType.EXECUTION, AuditEventType.VERIFICATION,
                AuditEventType.TRUST_UPDATE, AuditEventType.INCIDENT_CLOSED);
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    void escalatesWhenServiceDoesNotRecover() {
        when(verifier.verify(anyString(), any())).thenReturn(false);
        Incident incident = open(alert("ServiceDown", CONTAINER));

        orchestrator().heal(incident);

        assertThat(incidentStatus(incident.id())).isEqualTo("ESCALATED");
        assertThat(decisionOutcome(incident.id())).isEqualTo("FAILED");
        assertThat(trust.get(ActionType.RESTART).failures()).isEqualTo(1);
    }

    @Test
    void escalatesWhenTheActionFails() {
        doReturn(ExecutionResult.failed(ActionType.RESTART, CONTAINER, "docker unreachable"))
                .when(executor).execute(any(), anyString());
        Incident incident = open(alert("ServiceDown", CONTAINER));

        orchestrator().heal(incident);

        verify(verifier, never()).verify(anyString(), any());
        assertThat(incidentStatus(incident.id())).isEqualTo("ESCALATED");
        assertThat(trust.get(ActionType.RESTART).failures()).isEqualTo(1);
    }

    @Test
    void escalatesWhenNoRuleMatches() {
        Incident incident = open(alert("DiskAlmostFull", CONTAINER));

        orchestrator().heal(incident);

        verify(executor, never()).execute(any(), anyString());
        assertThat(incidentStatus(incident.id())).isEqualTo("ESCALATED");
    }

    @Test
    void rejectsTargetsThatAreNotOnTheAllowList() {
        Incident incident = open(alert("ServiceDown", "some-other-container"));

        orchestrator().heal(incident);

        verify(executor, never()).execute(any(), anyString());
        assertThat(incidentStatus(incident.id())).isEqualTo("ESCALATED");
        assertThat(decisionOutcome(incident.id())).isEqualTo("REJECTED");
    }

    @Test
    void doesNotRestartTheSameContainerTwiceInTheCooldown() {
        HealingOrchestrator orchestrator = orchestrator();
        orchestrator.heal(open(alert("ServiceDown", CONTAINER)));

        Incident second = open(alert("ServiceDown", CONTAINER));
        orchestrator.heal(second);

        verify(executor, times(1)).execute(ActionType.RESTART, CONTAINER);
        assertThat(incidentStatus(second.id())).isEqualTo("ESCALATED");
    }

    @Test
    void highRiskActionWaitsForApproval() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/policy.yaml").readAllBytes(), StandardCharsets.UTF_8)
                .replace("reversibility: 0.1     # a restart", "minLevel: L2_APPROVAL\n    reversibility: 0.1     # a restart");
        Policy strict = new PolicyLoader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test");
        Incident incident = open(alert("ServiceDown", CONTAINER));

        orchestrator(strict).heal(incident);

        verify(executor, never()).execute(any(), anyString());
        assertThat(incidentStatus(incident.id())).isEqualTo("OPEN");
        assertThat(decisionOutcome(incident.id())).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void handleSkipsAlertsThatAreAlreadyBeingHandled() {
        open(alert("ServiceDown", CONTAINER));

        orchestrator().handle(alert("ServiceDown", CONTAINER));

        verify(executor, never()).execute(any(), eq(CONTAINER));
    }

    @Test
    void handleOpensAndHealsNewAlert() {
        orchestrator().handle(alert("ServiceDown", CONTAINER));

        verify(executor).execute(ActionType.RESTART, CONTAINER);
        assertThat(incidents.findOpen("order-service", "ServiceDown")).isEmpty();
    }
}
