package edu.neu.strata.healer.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.metrics.HealerMetrics;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;
import edu.neu.strata.healer.persistence.IncidentRepository;

// L2 actions wait here for an engineer. Each decision can be approved, denied or time out once.
// If nobody answers in time it goes to a human, it never runs on its own.
@Component
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final IncidentRepository records;
    private final IncidentLifecycle lifecycle;
    private final ExecutionStage executionStage;
    private final AuditLogService audit;
    private final Clock clock;
    private final Duration timeout;
    private final Executor background;
    private HealerMetrics metrics = HealerMetrics.none();

    // Decisions someone already took. Only one caller gets to decide a given one.
    private final Set<Long> taken = ConcurrentHashMap.newKeySet();

    @Autowired
    public ApprovalService(IncidentRepository records, IncidentLifecycle lifecycle, ExecutionStage executionStage,
                           AuditLogService audit,
                           @Value("${strata.approval.timeout-minutes:30}") long timeoutMinutes) {
        this(records, lifecycle, executionStage, audit, Clock.systemUTC(), Duration.ofMinutes(timeoutMinutes),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    ApprovalService(IncidentRepository records, IncidentLifecycle lifecycle, ExecutionStage executionStage,
                    AuditLogService audit, Clock clock, Duration timeout, Executor background) {
        this.records = records;
        this.lifecycle = lifecycle;
        this.executionStage = executionStage;
        this.audit = audit;
        this.clock = clock;
        this.timeout = timeout;
        this.background = background;
    }

    // Optional, so the constructors stay as they are; Spring sets it when the healer runs
    @Autowired(required = false)
    void setMetrics(HealerMetrics metrics) {
        this.metrics = metrics;
    }

    public List<PendingApproval> pending() {
        return records.findAwaitingApproval().stream().map(this::describe).toList();
    }

    // The action runs in the background, so this returns as soon as the decision is taken
    public void approve(long decisionId, String by) {
        Waiting waiting = take(decisionId);
        audit.append(AuditEventType.APPROVAL, waiting.incident.id(), Map.of(
                "status", "approved", "decision", decisionId, "by", by));
        metrics.approval(HealerMetrics.ApprovalResult.APPROVED);
        background.execute(() -> execute(waiting));
    }

    public void deny(long decisionId, String by) {
        Waiting waiting = take(decisionId);
        audit.append(AuditEventType.APPROVAL, waiting.incident.id(), Map.of(
                "status", "denied", "decision", decisionId, "by", by));
        metrics.approval(HealerMetrics.ApprovalResult.DENIED);
        lifecycle.handToHuman(waiting.incident, waiting.decision, "denied by " + by);
    }

    @Scheduled(fixedDelayString = "${strata.approval.check-interval-ms:30000}")
    public int expireOverdue() {
        Instant now = clock.instant();
        int expired = 0;
        for (DecisionRecord record : records.findAwaitingApproval()) {
            if (record.decidedAt().plus(timeout).isAfter(now) || !taken.add(record.id())) {
                continue;
            }
            try {
                Waiting waiting = waitingFor(record);
                audit.append(AuditEventType.APPROVAL, waiting.incident.id(), Map.of(
                        "status", "timed out", "decision", record.id()));
                metrics.approval(HealerMetrics.ApprovalResult.TIMED_OUT);
                lifecycle.handToHuman(waiting.incident, waiting.decision,
                        "nobody answered within " + timeout.toMinutes() + " minutes");
                expired++;
            } catch (RuntimeException e) {
                log.error("Could not expire decision {}", record.id(), e);
            }
        }
        return expired;
    }

    private Waiting take(long decisionId) {
        DecisionRecord record = records.findDecision(decisionId)
                .orElseThrow(() -> new NoSuchElementException("no decision " + decisionId));
        if (!DecisionOutcome.AWAITING_APPROVAL.name().equals(record.outcome()) || !taken.add(decisionId)) {
            throw new IllegalStateException("decision " + decisionId + " is not waiting for approval any more");
        }
        return waitingFor(record);
    }

    private Waiting waitingFor(DecisionRecord record) {
        IncidentRecord stored = records.findById(record.incidentId()).orElseThrow();
        Alert alert = new Alert(stored.alertName(), stored.service(), stored.container(), stored.severity(),
                stored.openedAt());
        Incident incident = new Incident(stored.id(), alert, stored.openedAt());
        Diagnosis diagnosis = new Diagnosis(record.ruleId(), ActionType.valueOf(record.action()), record.target(),
                record.confidence());
        Decision decision = new Decision(record.id(), record.incidentId(), DecisionOutcome.AWAITING_APPROVAL);
        return new Waiting(incident, diagnosis, decision);
    }

    private void execute(Waiting waiting) {
        try {
            executionStage.run(waiting.incident, waiting.diagnosis, waiting.decision);
        } catch (RuntimeException e) {
            log.error("Approved decision {} failed", waiting.decision.id(), e);
            if (waiting.decision.outcome() == DecisionOutcome.AWAITING_APPROVAL) {
                lifecycle.move(waiting.decision, DecisionOutcome.FAILED);
            }
            lifecycle.escalateIfOpen(waiting.incident, "unexpected error: " + e.getMessage());
        }
    }

    private PendingApproval describe(DecisionRecord record) {
        String service = records.findById(record.incidentId()).map(IncidentRecord::service).orElse(null);
        return new PendingApproval(record.id(), record.incidentId(), service, record.ruleId(), record.action(),
                record.target(), record.confidence(), record.riskScore(), record.riskFactors(),
                preview(ActionType.valueOf(record.action()), record.target()),
                record.decidedAt(), record.decidedAt().plus(timeout));
    }

    private static String preview(ActionType action, String target) {
        return switch (action) {
            case RESTART -> "Restart " + target;
            case SCALE_OUT -> "Start one more copy of " + target;
            case ROLLBACK -> "Replace " + target + " with its previous image, one instance first";
        };
    }

    private record Waiting(Incident incident, Diagnosis diagnosis, Decision decision) {
    }
}
