package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.DecisionStore;
import edu.neu.strata.healer.persistence.IncidentStore;
import edu.neu.strata.healer.persistence.TestDatabase;

class IncidentLifecycleTest {

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final IncidentService incidents = new IncidentService(new IncidentStore(jdbc));
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
    private final IncidentLifecycle lifecycle = new IncidentLifecycle(incidents, new DecisionStore(jdbc), audit);

    private final Alert alert = new Alert("ServiceDown", "order-service", "strata-order-service-1", "critical",
            Instant.parse("2026-11-08T10:00:00Z"));
    private final Diagnosis diagnosis = new Diagnosis("service-down-restart", ActionType.RESTART,
            "strata-order-service-1", 0.9);
    private final RiskAssessment risk = new RiskAssessment(10, AutonomyLevel.L0_AUTO, Map.of("blastRadius", 5));

    private Incident openIncident() {
        return lifecycle.openIfNew(alert).orElseThrow();
    }

    private String incidentStatus(Incident incident) {
        return jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(incident.id()).query(String.class).single();
    }

    private String storedOutcome(Decision decision) {
        return jdbc.sql("SELECT outcome FROM decisions WHERE id = ?").param(decision.id()).query(String.class).single();
    }

    private List<AuditEventType> auditTypes(Incident incident) {
        return audit.forIncident(incident.id()).stream().map(AuditEntry::eventType).toList();
    }

    @Test
    void aRepeatedAlertDoesNotOpenAnotherIncident() {
        openIncident();

        assertThat(lifecycle.openIfNew(alert)).isEmpty();
    }

    @Test
    void aDecisionCanFollowTheNormalPath() {
        Incident incident = openIncident();
        Decision decision = lifecycle.propose(incident, diagnosis, risk);

        lifecycle.move(decision, DecisionOutcome.EXECUTED);
        lifecycle.move(decision, DecisionOutcome.RECOVERED);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.RECOVERED);
        assertThat(storedOutcome(decision)).isEqualTo("RECOVERED");
    }

    @Test
    void aDecisionCannotSkipAStep() {
        Decision decision = lifecycle.propose(openIncident(), diagnosis, risk);

        assertThatThrownBy(() -> lifecycle.move(decision, DecisionOutcome.RECOVERED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING to RECOVERED");
        assertThat(storedOutcome(decision)).isEqualTo("PENDING");
    }

    @Test
    void aFinishedDecisionCannotMoveAgain() {
        Decision decision = lifecycle.propose(openIncident(), diagnosis, risk);
        lifecycle.move(decision, DecisionOutcome.FAILED);

        assertThatThrownBy(() -> lifecycle.move(decision, DecisionOutcome.EXECUTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void awaitingApprovalKeepsTheIncidentOpenAndIsAudited() {
        Incident incident = openIncident();
        Decision decision = lifecycle.propose(incident, diagnosis, risk);

        lifecycle.awaitApproval(decision);

        assertThat(storedOutcome(decision)).isEqualTo("AWAITING_APPROVAL");
        assertThat(incidentStatus(incident)).isEqualTo("OPEN");
        assertThat(auditTypes(incident)).containsExactly(AuditEventType.APPROVAL);
    }

    @Test
    void resolvingClosesTheIncidentAndIsAudited() {
        Incident incident = openIncident();

        lifecycle.resolve(incident);

        assertThat(incidentStatus(incident)).isEqualTo("RESOLVED");
        assertThat(auditTypes(incident)).containsExactly(AuditEventType.INCIDENT_CLOSED);
    }

    @Test
    void anIncidentCannotBeClosedTwice() {
        Incident incident = openIncident();
        lifecycle.resolve(incident);

        assertThatThrownBy(() -> lifecycle.escalate(incident, "too late"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lifecycle.resolve(incident)).isInstanceOf(IllegalStateException.class);
        assertThat(incidentStatus(incident)).isEqualTo("RESOLVED");
    }

    @Test
    void escalateIfOpenLeavesAClosedIncidentAlone() {
        Incident incident = openIncident();
        lifecycle.resolve(incident);

        lifecycle.escalateIfOpen(incident, "error after the fact");

        assertThat(incidentStatus(incident)).isEqualTo("RESOLVED");
    }

    @Test
    void rejectingStoresARejectedDecisionAndEscalates() {
        Incident incident = openIncident();

        lifecycle.reject(incident, diagnosis, "target is not on the allow-list");

        assertThat(incidentStatus(incident)).isEqualTo("ESCALATED");
        assertThat(jdbc.sql("SELECT outcome FROM decisions WHERE incident_id = ?").param(incident.id())
                .query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void handingToAHumanMovesTheDecisionAndEscalates() {
        Incident incident = openIncident();
        Decision decision = lifecycle.propose(incident, diagnosis, risk);

        lifecycle.handToHuman(incident, decision, "needs a person");

        assertThat(storedOutcome(decision)).isEqualTo("HANDED_TO_HUMAN");
        assertThat(incidentStatus(incident)).isEqualTo("ESCALATED");
    }
}
