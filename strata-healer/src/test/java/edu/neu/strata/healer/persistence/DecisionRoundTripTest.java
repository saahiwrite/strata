package edu.neu.strata.healer.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;

/** Decisions written by {@link DecisionStore} come back from {@link IncidentRepository} with the same enums (#62). */
class DecisionRoundTripTest {

    private final JdbcClient jdbc = JdbcClient.create(new TestDatabase().dataSource);
    private final DecisionStore decisions = new DecisionStore(jdbc);
    private final IncidentRepository records = new IncidentRepository(jdbc);
    private final long incidentId = new IncidentStore(jdbc).insert(new Alert("ServiceDown", "order-service",
            "strata-order-service-1", "critical", Instant.parse("2026-11-08T10:00:00Z")),
            Instant.parse("2026-11-08T10:00:01Z")).id();

    private final Diagnosis diagnosis = new Diagnosis("service-down-restart", ActionType.SCALE_OUT,
            "strata-order-service-1", 0.9);
    private final RiskAssessment risk = new RiskAssessment(45, AutonomyLevel.L2_APPROVAL, Map.of("blastRadius", 10));

    // Every outcome fits the column and its check constraint, so none can fail only in production
    @ParameterizedTest
    @EnumSource(DecisionOutcome.class)
    void everyOutcomeIsStoredAndReadBack(DecisionOutcome outcome) {
        long id = decisions.insert(incidentId, diagnosis, null, risk, DecisionOutcome.PENDING);
        decisions.updateOutcome(id, outcome);

        DecisionRecord record = records.findDecision(id).orElseThrow();
        assertThat(record.outcome()).isEqualTo(outcome);
        assertThat(record.action()).isEqualTo(ActionType.SCALE_OUT);
        assertThat(record.autonomyLevel()).isEqualTo(AutonomyLevel.L2_APPROVAL);
        // Stored as the name, so the API and the dashboard see the same text as before
        assertThat(jdbc.sql("SELECT outcome FROM decisions WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo(outcome.name());
    }

    @Test
    void rejectedDecisionHasNoLevel() {
        long id = decisions.insert(incidentId, diagnosis, "not on the allow-list", null, DecisionOutcome.REJECTED);

        DecisionRecord record = records.findDecision(id).orElseThrow();
        assertThat(record.outcome()).isEqualTo(DecisionOutcome.REJECTED);
        assertThat(record.autonomyLevel()).isNull();
        assertThat(record.allowed()).isFalse();
    }

    @Test
    void onlyDecisionsAwaitingApprovalAreListedForApproval() {
        long waiting = decisions.insert(incidentId, diagnosis, null, risk, DecisionOutcome.AWAITING_APPROVAL);
        decisions.insert(incidentId, diagnosis, null, risk, DecisionOutcome.EXECUTED);

        assertThat(records.findAwaitingApproval()).extracting(DecisionRecord::id).containsExactly(waiting);
    }
}
