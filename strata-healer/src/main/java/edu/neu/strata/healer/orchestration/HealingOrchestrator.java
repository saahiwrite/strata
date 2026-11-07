package edu.neu.strata.healer.orchestration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.DecisionStore;
import edu.neu.strata.healer.policy.RiskScorer;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.validation.ActionValidator;
import edu.neu.strata.healer.validation.ValidationResult;
import edu.neu.strata.healer.verification.RecoveryVerifier;

// detect -> diagnose -> validate -> score -> execute -> verify -> audit -> trust
@Component
public class HealingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HealingOrchestrator.class);

    private static final Duration LOG_WINDOW = Duration.ofMinutes(5);
    private static final int LOG_LIMIT = 50;

    private final IncidentService incidents;
    private final LokiLogClient logClient;
    private final RuleMatcher ruleMatcher;
    private final ActionValidator validator;
    private final RiskScorer riskScorer;
    private final TrustService trust;
    private final CooldownGuard cooldown;
    private final ActionExecutor executor;
    private final RecoveryVerifier verifier;
    private final AuditLogService audit;
    private final DecisionStore decisions;
    private final Duration verifyTimeout;
    private final Executor background;

    @Autowired
    public HealingOrchestrator(IncidentService incidents, LokiLogClient logClient, RuleMatcher ruleMatcher,
                               ActionValidator validator, RiskScorer riskScorer, TrustService trust,
                               CooldownGuard cooldown, ActionExecutor executor, RecoveryVerifier verifier,
                               AuditLogService audit, DecisionStore decisions,
                               @Value("${strata.verify.timeout-seconds:30}") long verifySeconds) {
        this(incidents, logClient, ruleMatcher, validator, riskScorer, trust, cooldown, executor, verifier,
                audit, decisions, Duration.ofSeconds(verifySeconds), Executors.newVirtualThreadPerTaskExecutor());
    }

    HealingOrchestrator(IncidentService incidents, LokiLogClient logClient, RuleMatcher ruleMatcher,
                        ActionValidator validator, RiskScorer riskScorer, TrustService trust,
                        CooldownGuard cooldown, ActionExecutor executor, RecoveryVerifier verifier,
                        AuditLogService audit, DecisionStore decisions, Duration verifyTimeout,
                        Executor background) {
        this.incidents = incidents;
        this.logClient = logClient;
        this.ruleMatcher = ruleMatcher;
        this.validator = validator;
        this.riskScorer = riskScorer;
        this.trust = trust;
        this.cooldown = cooldown;
        this.executor = executor;
        this.verifier = verifier;
        this.audit = audit;
        this.decisions = decisions;
        this.verifyTimeout = verifyTimeout;
        this.background = background;
    }

    // Called by the webhook. Opens the incident and heals it in the background.
    public void handle(Alert alert) {
        if (incidents.findOpen(alert.service(), alert.alertName()).isPresent()) {
            log.info("{} on {} is already being handled", alert.alertName(), alert.service());
            return;
        }
        Incident incident = incidents.open(alert);
        background.execute(() -> heal(incident));
    }

    public void heal(Incident incident) {
        try {
            run(incident);
        } catch (RuntimeException e) {
            log.error("Healing incident {} failed", incident.id(), e);
            closeAsEscalated(incident, "unexpected error: " + e.getMessage());
        }
    }

    private void run(Incident incident) {
        Alert alert = incident.alert();
        long id = incident.id();
        audit.append(AuditEventType.INCIDENT_OPENED, id, Map.of(
                "alertName", alert.alertName(),
                "service", alert.service(),
                "container", String.valueOf(alert.container())));

        List<String> logs = logClient.recentLogs(logSource(alert), LOG_WINDOW, LOG_LIMIT);
        Optional<Diagnosis> found = ruleMatcher.match(alert, logs);
        if (found.isEmpty()) {
            audit.append(AuditEventType.DIAGNOSIS, id, Map.of("result", "no rule matched"));
            closeAsEscalated(incident, "no rule matched");
            return;
        }
        Diagnosis diagnosis = found.get();
        audit.append(AuditEventType.DIAGNOSIS, id, Map.of(
                "ruleId", diagnosis.ruleId(),
                "action", diagnosis.proposedAction().name(),
                "target", diagnosis.target(),
                "confidence", diagnosis.confidence()));

        ValidationResult validation = validator.validate(diagnosis);
        audit.append(AuditEventType.VALIDATION, id, Map.of(
                "allowed", validation.allowed(),
                "reason", validation.reason()));
        if (!validation.allowed()) {
            decisions.insert(id, diagnosis, validation.reason(), null, "REJECTED");
            closeAsEscalated(incident, "rejected: " + validation.reason());
            return;
        }

        RiskAssessment risk = riskScorer.assess(diagnosis, trust.history(diagnosis.proposedAction()));
        audit.append(AuditEventType.RISK_ASSESSMENT, id, Map.of(
                "score", risk.score(),
                "level", risk.level().name(),
                "factors", risk.factors()));
        long decisionId = decisions.insert(id, diagnosis, null, risk, "PENDING");

        if (risk.level() == AutonomyLevel.L2_APPROVAL) {
            decisions.updateOutcome(decisionId, "AWAITING_APPROVAL");
            audit.append(AuditEventType.APPROVAL, id, Map.of("status", "waiting for approval"));
            return;
        }
        if (risk.level() == AutonomyLevel.L3_HUMAN_ONLY) {
            decisions.updateOutcome(decisionId, "HANDED_TO_HUMAN");
            closeAsEscalated(incident, "risk level needs a human");
            return;
        }

        execute(incident, diagnosis, decisionId);
    }

    private void execute(Incident incident, Diagnosis diagnosis, long decisionId) {
        long id = incident.id();
        ActionType action = diagnosis.proposedAction();
        String target = diagnosis.target();

        if (cooldown.isCoolingDown(target)) {
            audit.append(AuditEventType.EXECUTION, id, Map.of("skipped", "cooling down", "target", target));
            decisions.updateOutcome(decisionId, "HANDED_TO_HUMAN");
            closeAsEscalated(incident, target + " was acted on recently");
            return;
        }

        ExecutionResult result = executor.execute(action, target);
        cooldown.markExecuted(target);
        audit.append(AuditEventType.EXECUTION, id, Map.of(
                "action", action.name(),
                "target", target,
                "success", result.success(),
                "detail", result.detail()));
        if (!result.success()) {
            decisions.updateOutcome(decisionId, "FAILED");
            trust.recordFailure(action, id);
            closeAsEscalated(incident, "action failed: " + result.detail());
            return;
        }
        decisions.updateOutcome(decisionId, "EXECUTED");

        boolean recovered = verifier.verify(incident.alert().service(), verifyTimeout);
        audit.append(AuditEventType.VERIFICATION, id, Map.of(
                "service", incident.alert().service(),
                "recovered", recovered));

        if (recovered) {
            decisions.updateOutcome(decisionId, "RECOVERED");
            trust.recordSuccess(action, id);
            incidents.resolve(id);
            audit.append(AuditEventType.INCIDENT_CLOSED, id, Map.of("status", "RESOLVED"));
        } else {
            decisions.updateOutcome(decisionId, "FAILED");
            trust.recordFailure(action, id);
            closeAsEscalated(incident, "service did not recover");
        }
    }

    private void closeAsEscalated(Incident incident, String reason) {
        incidents.escalate(incident.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", "ESCALATED");
        details.put("reason", reason);
        audit.append(AuditEventType.INCIDENT_CLOSED, incident.id(), details);
    }

    private String logSource(Alert alert) {
        return alert.container() != null ? alert.container() : alert.service();
    }
}
