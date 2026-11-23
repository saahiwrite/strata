package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentService;
import edu.neu.strata.healer.execution.ActionExecutor;
import edu.neu.strata.healer.execution.CooldownGuard;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.DecisionStore;
import edu.neu.strata.healer.persistence.IncidentStore;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.verification.RecoveryVerifier;

class ExecutionStageTest {

    private static final String TARGET = "strata-order-service-1";

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final IncidentService incidents = new IncidentService(new IncidentStore(jdbc));
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
    private final TrustService trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, Clock.systemUTC());
    private final IncidentLifecycle lifecycle = new IncidentLifecycle(incidents, new DecisionStore(jdbc), audit);
    private final ActionExecutor executor = mock(ActionExecutor.class);
    private final RecoveryVerifier verifier = mock(RecoveryVerifier.class);
    private final CooldownGuard cooldown = new CooldownGuard(Duration.ofMinutes(5), Clock.systemUTC());
    private final ExecutionStage stage = new ExecutionStage(cooldown, executor, verifier, trust, audit, lifecycle,
            Duration.ofSeconds(1));

    private final Alert alert = new Alert("ServiceDown", "order-service", TARGET, "critical",
            Instant.parse("2026-11-08T10:00:00Z"));
    private final Diagnosis diagnosis = new Diagnosis("service-down-restart", ActionType.RESTART, TARGET, 0.9);
    private final Incident incident = incidents.open(alert);
    private final Decision decision = lifecycle.propose(incident, diagnosis,
            new RiskAssessment(10, AutonomyLevel.L0_AUTO, Map.of()));

    private String incidentStatus() {
        return jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(incident.id()).query(String.class).single();
    }

    @Test
    void aRecoveredServiceResolvesTheIncidentAndCountsAsASuccess() {
        when(executor.execute(ActionType.RESTART, TARGET)).thenReturn(ExecutionResult.succeeded(ActionType.RESTART, TARGET));
        when(verifier.verify(anyString(), any())).thenReturn(true);

        stage.run(incident, diagnosis, decision);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.RECOVERED);
        assertThat(incidentStatus()).isEqualTo("RESOLVED");
        assertThat(trust.get(ActionType.RESTART).successes()).isEqualTo(1);
    }

    @Test
    void aServiceThatDoesNotRecoverIsEscalatedAndCountsAsAFailure() {
        when(executor.execute(ActionType.RESTART, TARGET)).thenReturn(ExecutionResult.succeeded(ActionType.RESTART, TARGET));
        when(verifier.verify(anyString(), any())).thenReturn(false);

        stage.run(incident, diagnosis, decision);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.FAILED);
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
        assertThat(trust.get(ActionType.RESTART).failures()).isEqualTo(1);
    }

    @Test
    void aFailedActionIsNotVerified() {
        when(executor.execute(ActionType.RESTART, TARGET))
                .thenReturn(ExecutionResult.failed(ActionType.RESTART, TARGET, "docker unreachable"));

        stage.run(incident, diagnosis, decision);

        verify(verifier, never()).verify(anyString(), any());
        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.FAILED);
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
        assertThat(trust.get(ActionType.RESTART).failures()).isEqualTo(1);
    }

    @Test
    void aContainerInItsCooldownIsHandedToAHuman() {
        cooldown.markExecuted(TARGET);

        stage.run(incident, diagnosis, decision);

        verify(executor, never()).execute(any(), anyString());
        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.HANDED_TO_HUMAN);
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
    }
}
