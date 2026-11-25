package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentService;
import edu.neu.strata.healer.execution.ActionExecutor;
import edu.neu.strata.healer.execution.CooldownGuard;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.DecisionStore;
import edu.neu.strata.healer.persistence.IncidentRepository;
import edu.neu.strata.healer.persistence.IncidentStore;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.verification.RecoveryVerifier;

class ApprovalServiceTest {

    private static final String TARGET = "strata-order-service-1";
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final IncidentService incidents = new IncidentService(new IncidentStore(jdbc));
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
    private final TrustService trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, Clock.systemUTC());
    private final IncidentLifecycle lifecycle = new IncidentLifecycle(incidents, new DecisionStore(jdbc), audit);
    private final ActionExecutor executor = mock(ActionExecutor.class);
    private final RecoveryVerifier verifier = mock(RecoveryVerifier.class);
    private final ExecutionStage stage = new ExecutionStage(new CooldownGuard(Duration.ofMinutes(5), Clock.systemUTC()),
            executor, verifier, trust, audit, lifecycle, Duration.ofSeconds(1));
    private final IncidentRepository records = new IncidentRepository(jdbc);
    private final ApprovalService approvals = serviceAt(Clock.systemUTC());

    private final Alert alert = new Alert("HighErrorRate", "order-service", TARGET, "critical",
            Instant.parse("2026-11-08T10:00:00Z"));
    private final Diagnosis diagnosis = new Diagnosis("error-rate-after-deploy-rollback", ActionType.ROLLBACK, TARGET, 0.8);
    private final Incident incident = incidents.open(alert);
    private final Decision decision = waitingDecision();

    private ApprovalService serviceAt(Clock clock) {
        return new ApprovalService(records, lifecycle, stage, audit, clock, TIMEOUT, Runnable::run);
    }

    private Decision waitingDecision() {
        Decision created = lifecycle.propose(incident, diagnosis,
                new RiskAssessment(40, AutonomyLevel.L2_APPROVAL, Map.of("blastRadius", 20)));
        lifecycle.awaitApproval(created);
        return created;
    }

    private String incidentStatus() {
        return jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(incident.id()).query(String.class).single();
    }

    private String outcome() {
        return jdbc.sql("SELECT outcome FROM decisions WHERE id = ?").param(decision.id()).query(String.class).single();
    }

    private List<String> approvalPayloads() {
        return audit.forIncident(incident.id()).stream()
                .filter(e -> e.eventType() == AuditEventType.APPROVAL)
                .map(AuditEntry::payload)
                .toList();
    }

    @Test
    void waitingDecisionsAreListedWithAPreviewAndADeadline() {
        List<PendingApproval> pending = approvals.pending();

        assertThat(pending).hasSize(1);
        PendingApproval first = pending.get(0);
        assertThat(first.decisionId()).isEqualTo(decision.id());
        assertThat(first.action()).isEqualTo("ROLLBACK");
        assertThat(first.target()).isEqualTo(TARGET);
        assertThat(first.service()).isEqualTo("order-service");
        assertThat(first.preview()).contains(TARGET).contains("previous image");
        assertThat(first.expiresAt()).isEqualTo(first.requestedAt().plus(TIMEOUT));
    }

    @Test
    void approvingRunsTheActionAndResolvesTheIncident() {
        when(executor.execute(ActionType.ROLLBACK, TARGET)).thenReturn(ExecutionResult.succeeded(ActionType.ROLLBACK, TARGET));
        when(verifier.verify(anyString(), any())).thenReturn(true);

        approvals.approve(decision.id(), "madhuri");

        verify(executor).execute(ActionType.ROLLBACK, TARGET);
        assertThat(outcome()).isEqualTo("RECOVERED");
        assertThat(incidentStatus()).isEqualTo("RESOLVED");
        assertThat(approvalPayloads()).anyMatch(p -> p.contains("\"approved\""));
        assertThat(approvals.pending()).isEmpty();
    }

    @Test
    void aDecisionCanOnlyBeApprovedOnce() {
        when(executor.execute(ActionType.ROLLBACK, TARGET)).thenReturn(ExecutionResult.succeeded(ActionType.ROLLBACK, TARGET));
        when(verifier.verify(anyString(), any())).thenReturn(true);
        approvals.approve(decision.id(), "madhuri");

        assertThatThrownBy(() -> approvals.approve(decision.id(), "someone else"))
                .isInstanceOf(IllegalStateException.class);

        verify(executor, times(1)).execute(any(), anyString());
    }

    @Test
    void denyingHandsTheIncidentToAHumanAndRunsNothing() {
        approvals.deny(decision.id(), "madhuri");

        verify(executor, never()).execute(any(), anyString());
        assertThat(outcome()).isEqualTo("HANDED_TO_HUMAN");
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
        assertThat(approvalPayloads()).anyMatch(p -> p.contains("\"denied\""));
    }

    @Test
    void aDeniedDecisionCannotBeApprovedLater() {
        approvals.deny(decision.id(), "madhuri");

        assertThatThrownBy(() -> approvals.approve(decision.id(), "madhuri"))
                .isInstanceOf(IllegalStateException.class);

        verify(executor, never()).execute(any(), anyString());
    }

    @Test
    void anApprovedDecisionCannotBeDeniedLater() {
        when(executor.execute(ActionType.ROLLBACK, TARGET)).thenReturn(ExecutionResult.succeeded(ActionType.ROLLBACK, TARGET));
        when(verifier.verify(anyString(), any())).thenReturn(true);
        approvals.approve(decision.id(), "madhuri");

        assertThatThrownBy(() -> approvals.deny(decision.id(), "madhuri"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownDecisionIsNotFound() {
        assertThatThrownBy(() -> approvals.approve(9999, "madhuri")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> approvals.deny(9999, "madhuri")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aDecisionThatNeverWaitedCannotBeApproved() {
        Incident other = incidents.open(new Alert("HighLatency", "inventory-service", "strata-inventory-service-1",
                "warning", Instant.parse("2026-11-08T10:05:00Z")));
        Decision autoRun = lifecycle.propose(other, new Diagnosis("latency", ActionType.RESTART,
                "strata-inventory-service-1", 0.9), new RiskAssessment(5, AutonomyLevel.L0_AUTO, Map.of()));

        assertThatThrownBy(() -> approvals.approve(autoRun.id(), "madhuri")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anActionThatFailsAfterApprovalEscalatesTheIncident() {
        when(executor.execute(ActionType.ROLLBACK, TARGET)).thenThrow(new IllegalStateException("docker is down"));

        approvals.approve(decision.id(), "madhuri");

        assertThat(outcome()).isEqualTo("FAILED");
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
    }

    @Test
    void anUnansweredRequestGoesToAHumanAfterTheTimeout() {
        ApprovalService later = serviceAt(Clock.fixed(Instant.now().plus(TIMEOUT).plusSeconds(60), ZoneOffset.UTC));

        int expired = later.expireOverdue();

        assertThat(expired).isEqualTo(1);
        verify(executor, never()).execute(any(), anyString());
        assertThat(outcome()).isEqualTo("HANDED_TO_HUMAN");
        assertThat(incidentStatus()).isEqualTo("ESCALATED");
        assertThat(approvalPayloads()).anyMatch(p -> p.contains("\"timed out\""));
    }

    @Test
    void aRequestInsideTheTimeoutIsLeftAlone() {
        ApprovalService soon = serviceAt(Clock.fixed(Instant.now().plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        assertThat(soon.expireOverdue()).isZero();
        assertThat(outcome()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void anExpiredRequestCannotBeApprovedAnyMore() {
        serviceAt(Clock.fixed(Instant.now().plus(TIMEOUT).plusSeconds(60), ZoneOffset.UTC)).expireOverdue();

        assertThatThrownBy(() -> approvals.approve(decision.id(), "madhuri")).isInstanceOf(IllegalStateException.class);

        verify(executor, never()).execute(any(), anyString());
    }
}
