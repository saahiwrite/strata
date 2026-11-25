package edu.neu.strata.healer.orchestration;

import java.util.Map;

import org.springframework.stereotype.Component;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.policy.RiskScorer;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.validation.ActionValidator;
import edu.neu.strata.healer.validation.ValidationResult;

// Checks a proposed action against the allow-list and scores its risk
@Component
public class AssessmentStage {

    // rejectionReason is null when the action is allowed, and risk is null when it is rejected
    public record Result(String rejectionReason, RiskAssessment risk) {

        public boolean allowed() {
            return rejectionReason == null;
        }
    }

    private final ActionValidator validator;
    private final RiskScorer riskScorer;
    private final TrustService trust;
    private final AuditLogService audit;

    public AssessmentStage(ActionValidator validator, RiskScorer riskScorer, TrustService trust,
                           AuditLogService audit) {
        this.validator = validator;
        this.riskScorer = riskScorer;
        this.trust = trust;
        this.audit = audit;
    }

    public Result assess(Incident incident, Diagnosis diagnosis) {
        long id = incident.id();

        ValidationResult validation = validator.validate(diagnosis);
        audit.append(AuditEventType.VALIDATION, id, Map.of(
                "allowed", validation.allowed(),
                "reason", validation.reason()));
        if (!validation.allowed()) {
            return new Result(validation.reason(), null);
        }

        RiskAssessment risk = riskScorer.assess(diagnosis, trust.history(diagnosis.proposedAction()));
        audit.append(AuditEventType.RISK_ASSESSMENT, id, Map.of(
                "score", risk.score(),
                "level", risk.level().name(),
                "factors", risk.factors()));
        return new Result(null, risk);
    }
}
