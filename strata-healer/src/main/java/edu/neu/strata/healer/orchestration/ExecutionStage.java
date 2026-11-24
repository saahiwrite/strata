package edu.neu.strata.healer.orchestration;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.execution.ActionExecutor;
import edu.neu.strata.healer.execution.CooldownGuard;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.verification.RecoveryVerifier;

// Runs an approved action: cooldown check, execute, verify recovery, update the trust record
@Component
public class ExecutionStage {

    private final CooldownGuard cooldown;
    private final ActionExecutor executor;
    private final RecoveryVerifier verifier;
    private final TrustService trust;
    private final AuditLogService audit;
    private final IncidentLifecycle lifecycle;
    private final Duration verifyTimeout;

    @Autowired
    public ExecutionStage(CooldownGuard cooldown, ActionExecutor executor, RecoveryVerifier verifier,
                          TrustService trust, AuditLogService audit, IncidentLifecycle lifecycle,
                          @Value("${strata.verify.timeout-seconds:30}") long verifySeconds) {
        this(cooldown, executor, verifier, trust, audit, lifecycle, Duration.ofSeconds(verifySeconds));
    }

    ExecutionStage(CooldownGuard cooldown, ActionExecutor executor, RecoveryVerifier verifier,
                   TrustService trust, AuditLogService audit, IncidentLifecycle lifecycle,
                   Duration verifyTimeout) {
        this.cooldown = cooldown;
        this.executor = executor;
        this.verifier = verifier;
        this.trust = trust;
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.verifyTimeout = verifyTimeout;
    }

    public void run(Incident incident, Diagnosis diagnosis, Decision decision) {
        long id = incident.id();
        ActionType action = diagnosis.proposedAction();
        String target = diagnosis.target();

        if (cooldown.isCoolingDown(target)) {
            audit.append(AuditEventType.EXECUTION, id, Map.of("skipped", "cooling down", "target", target));
            lifecycle.handToHuman(incident, decision, target + " was acted on recently");
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
            lifecycle.move(decision, DecisionOutcome.FAILED);
            trust.recordFailure(action, id);
            lifecycle.escalate(incident, "action failed: " + result.detail());
            return;
        }
        lifecycle.move(decision, DecisionOutcome.EXECUTED);

        String service = incident.alert().service();
        boolean recovered = verifier.verify(service, verifyTimeout);
        audit.append(AuditEventType.VERIFICATION, id, Map.of("service", service, "recovered", recovered));

        if (recovered) {
            lifecycle.move(decision, DecisionOutcome.RECOVERED);
            trust.recordSuccess(action, id);
            lifecycle.resolve(incident);
        } else {
            lifecycle.move(decision, DecisionOutcome.FAILED);
            trust.recordFailure(action, id);
            lifecycle.escalate(incident, "service did not recover");
        }
    }
}
