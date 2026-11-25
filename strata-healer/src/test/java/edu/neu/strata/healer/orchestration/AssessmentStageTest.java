package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.policy.Policy;
import edu.neu.strata.healer.policy.PolicyLoader;
import edu.neu.strata.healer.policy.RiskScorer;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.validation.ActionValidator;

class AssessmentStageTest {

    private static final Alert ALERT = new Alert("ServiceDown", "order-service", "strata-order-service-1",
            "critical", Instant.parse("2026-11-08T10:00:00Z"));
    private static final Incident INCIDENT = new Incident(1, ALERT, ALERT.startsAt());

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
    private final TrustService trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, Clock.systemUTC());
    private final Policy policy = new PolicyLoader().loadDefault();
    private final AssessmentStage stage = new AssessmentStage(new ActionValidator(policy), new RiskScorer(policy), trust, audit);

    private List<AuditEventType> auditTypes() {
        return audit.forIncident(1).stream().map(AuditEntry::eventType).toList();
    }

    @Test
    void anAllowedRestartIsScoredAsLowRisk() {
        Diagnosis restart = new Diagnosis("service-down-restart", ActionType.RESTART, "strata-order-service-1", 0.9);

        AssessmentStage.Result result = stage.assess(INCIDENT, restart);

        assertThat(result.allowed()).isTrue();
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.risk().level()).isEqualTo(AutonomyLevel.L0_AUTO);
        assertThat(auditTypes()).containsExactly(AuditEventType.VALIDATION, AuditEventType.RISK_ASSESSMENT);
    }

    @Test
    void aTargetThatIsNotOnTheAllowListIsRejectedWithoutScoring() {
        Diagnosis bad = new Diagnosis("service-down-restart", ActionType.RESTART, "postgres", 0.9);

        AssessmentStage.Result result = stage.assess(INCIDENT, bad);

        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("postgres");
        assertThat(result.risk()).isNull();
        assertThat(auditTypes()).containsExactly(AuditEventType.VALIDATION);
    }

    @Test
    void aRollbackNeedsApproval() {
        Diagnosis rollback = new Diagnosis("error-rate-after-deploy-rollback", ActionType.ROLLBACK,
                "strata-order-service-1", 0.8);

        assertThat(stage.assess(INCIDENT, rollback).risk().level()).isEqualTo(AutonomyLevel.L2_APPROVAL);
    }

    @Test
    void pastFailuresMakeAnActionRiskier() {
        Diagnosis restart = new Diagnosis("service-down-restart", ActionType.RESTART, "strata-order-service-1", 0.9);
        int before = stage.assess(INCIDENT, restart).risk().score();

        trust.recordFailure(ActionType.RESTART, 1L);
        trust.recordFailure(ActionType.RESTART, 1L);

        assertThat(stage.assess(INCIDENT, restart).risk().score()).isGreaterThan(before);
    }
}
