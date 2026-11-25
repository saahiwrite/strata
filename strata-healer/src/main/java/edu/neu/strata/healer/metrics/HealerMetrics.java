package edu.neu.strata.healer.metrics;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentStatus;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.DiagnosisSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The healer's own numbers over time (#64), for the benchmark (#46) and the research question:
 * how many incidents it sees, how fast they recover (MTTR), and how freely it acts.
 *
 * <p>Exposed at {@code /actuator/prometheus} and scraped by Prometheus as job {@code strata-healer};
 * the Grafana dashboard's "STRATA decisions" row draws them. In Prometheus the names get a
 * {@code strata_} prefix with dots turned into underscores, for example
 * {@code strata_incidents_opened_total} and {@code strata_incident_recovery_seconds_count}.
 *
 * <p>Every method only counts; none of them can change what the healer does. The per-action
 * autonomy gauges are registered by {@link AutonomyGauges}.
 */
@Component
public class HealerMetrics {

    /** Incidents opened (one per new alert). */
    public static final String INCIDENTS_OPENED = "strata.incidents.opened";
    /** Incidents closed, tagged {@code status} = RESOLVED or ESCALATED. */
    public static final String INCIDENTS_CLOSED = "strata.incidents.closed";
    /** Time from the alert firing to the incident being RESOLVED: the healer's MTTR. */
    public static final String RECOVERY = "strata.incident.recovery";
    /** Actions scored, tagged {@code level} (L0-L3) and {@code source} (RULE or LLM). */
    public static final String DECISIONS = "strata.decisions";
    /** Every outcome a decision reaches, tagged {@code outcome} (see {@link DecisionOutcome}). */
    public static final String DECISION_OUTCOMES = "strata.decision.outcomes";
    /** Proposals the allow-list refused before scoring, tagged {@code source}. */
    public static final String PROPOSALS_REJECTED = "strata.proposals.rejected";
    /** Answers to L2 approvals, tagged {@code result} = approved, denied or timed_out. */
    public static final String APPROVALS = "strata.approvals";
    /** Incidents whose logs held planted instructions, so the LLM was not asked (#53). */
    public static final String INJECTION_SUSPECTED = "strata.injection.suspected";

    private final MeterRegistry registry;
    private final Clock clock;

    @Autowired
    public HealerMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    public HealerMetrics(MeterRegistry registry, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        registerAtZero();
    }

    // Every series exists from startup at 0. A counter first seen at 1 looks to Prometheus'
    // increase() like it was always 1, so the dashboard would miss the first incident.
    private void registerAtZero() {
        registry.counter(INCIDENTS_OPENED);
        registry.counter(INJECTION_SUSPECTED);
        for (IncidentStatus status : new IncidentStatus[] {IncidentStatus.RESOLVED, IncidentStatus.ESCALATED}) {
            registry.counter(INCIDENTS_CLOSED, "status", status.name());
        }
        for (DiagnosisSource source : DiagnosisSource.values()) {
            registry.counter(PROPOSALS_REJECTED, "source", source.name());
            for (AutonomyLevel level : AutonomyLevel.values()) {
                registry.counter(DECISIONS, "level", level.name(), "source", source.name());
            }
        }
        for (DecisionOutcome outcome : DecisionOutcome.values()) {
            registry.counter(DECISION_OUTCOMES, "outcome", outcome.name());
        }
        for (ApprovalResult result : ApprovalResult.values()) {
            registry.counter(APPROVALS, "result", result.tag());
        }
        recoveryTimer();
    }

    /** Metrics that go nowhere; for classes built by hand in tests. */
    public static HealerMetrics none() {
        return new HealerMetrics(new SimpleMeterRegistry());
    }

    public void incidentOpened() {
        registry.counter(INCIDENTS_OPENED).increment();
    }

    /** Counts the close and, for a recovery, times it from when the alert started firing. */
    public void incidentClosed(Incident incident, IncidentStatus status) {
        registry.counter(INCIDENTS_CLOSED, "status", status.name()).increment();
        if (status == IncidentStatus.RESOLVED) {
            Duration took = Duration.between(incident.alert().startsAt(), clock.instant());
            recoveryTimer().record(took.isNegative() ? Duration.ZERO : took);
        }
    }

    public void decisionScored(Diagnosis diagnosis, AutonomyLevel level) {
        registry.counter(DECISIONS, "level", level.name(), "source", diagnosis.source().name()).increment();
    }

    public void decisionOutcome(DecisionOutcome outcome) {
        registry.counter(DECISION_OUTCOMES, "outcome", outcome.name()).increment();
    }

    public void proposalRejected(Diagnosis diagnosis) {
        registry.counter(PROPOSALS_REJECTED, "source", diagnosis.source().name()).increment();
        decisionOutcome(DecisionOutcome.REJECTED);
    }

    public void approval(ApprovalResult result) {
        registry.counter(APPROVALS, "result", result.tag()).increment();
    }

    public void injectionSuspected() {
        registry.counter(INJECTION_SUSPECTED).increment();
    }

    private Timer recoveryTimer() {
        return Timer.builder(RECOVERY)
                .description("Time from the alert firing to the incident being resolved (MTTR)")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofMinutes(30))
                .register(registry);
    }

    /** How an L2 approval ended. */
    public enum ApprovalResult {
        APPROVED, DENIED, TIMED_OUT;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
