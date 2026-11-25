package edu.neu.strata.healer.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentStatus;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.DiagnosisSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HealerMetricsTest {

    private static final Instant FIRED = Instant.parse("2026-11-20T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HealerMetrics metrics =
            new HealerMetrics(registry, Clock.fixed(FIRED.plusSeconds(42), ZoneOffset.UTC));

    private final Incident incident = new Incident(1,
            new Alert("ServiceDown", "order-service", "strata-order-service-1", "critical", FIRED),
            FIRED.plusSeconds(5));

    private double count(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void recoveryIsTimedFromWhenTheAlertFired() {
        metrics.incidentClosed(incident, IncidentStatus.RESOLVED);

        var timer = registry.get(HealerMetrics.RECOVERY).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(42.0);
        assertThat(count(HealerMetrics.INCIDENTS_CLOSED, "status", "RESOLVED")).isEqualTo(1);
    }

    @Test
    void escalationIsCountedButNotTimed() {
        metrics.incidentClosed(incident, IncidentStatus.ESCALATED);

        assertThat(count(HealerMetrics.INCIDENTS_CLOSED, "status", "ESCALATED")).isEqualTo(1);
        assertThat(registry.get(HealerMetrics.RECOVERY).timer().count()).isZero();
    }

    @Test
    void decisionsAreCountedByLevelAndSource() {
        Diagnosis rule = new Diagnosis("service-down-restart", ActionType.RESTART, "strata-order-service-1", 0.9);
        Diagnosis llm = new Diagnosis("llm:qwen", ActionType.RESTART, "strata-order-service-1", 0.6,
                DiagnosisSource.LLM);

        metrics.decisionScored(rule, AutonomyLevel.L0_AUTO);
        metrics.decisionScored(rule, AutonomyLevel.L0_AUTO);
        metrics.decisionScored(llm, AutonomyLevel.L2_APPROVAL);
        metrics.proposalRejected(llm);

        assertThat(count(HealerMetrics.DECISIONS, "level", "L0_AUTO", "source", "RULE")).isEqualTo(2);
        assertThat(count(HealerMetrics.DECISIONS, "level", "L2_APPROVAL", "source", "LLM")).isEqualTo(1);
        assertThat(count(HealerMetrics.PROPOSALS_REJECTED, "source", "LLM")).isEqualTo(1);
        assertThat(count(HealerMetrics.DECISION_OUTCOMES, "outcome", DecisionOutcome.REJECTED.name())).isEqualTo(1);
    }

    @Test
    void approvalsAndInjectionsAreCounted() {
        metrics.approval(HealerMetrics.ApprovalResult.APPROVED);
        metrics.approval(HealerMetrics.ApprovalResult.TIMED_OUT);
        metrics.injectionSuspected();

        assertThat(count(HealerMetrics.APPROVALS, "result", "approved")).isEqualTo(1);
        assertThat(count(HealerMetrics.APPROVALS, "result", "timed_out")).isEqualTo(1);
        assertThat(count(HealerMetrics.APPROVALS, "result", "denied")).isZero();
        assertThat(count(HealerMetrics.INJECTION_SUSPECTED)).isEqualTo(1);
    }

    @Test
    void everySeriesStartsAtZero() {
        // Without this, Prometheus' increase() never sees the first incident on the dashboard
        assertThat(registry.get(HealerMetrics.INCIDENTS_OPENED).counter().count()).isZero();
        assertThat(registry.get(HealerMetrics.DECISIONS).tags("level", "L3_HUMAN_ONLY", "source", "LLM").counter())
                .isNotNull();
        assertThat(registry.get(HealerMetrics.DECISION_OUTCOMES).counters()).hasSize(DecisionOutcome.values().length);
        assertThat(registry.get(HealerMetrics.APPROVALS).counters()).hasSize(3);
        assertThat(registry.get(HealerMetrics.RECOVERY).timer().count()).isZero();
    }
}
