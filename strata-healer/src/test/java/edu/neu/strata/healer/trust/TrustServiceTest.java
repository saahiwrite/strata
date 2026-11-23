package edu.neu.strata.healer.trust;

import static org.assertj.core.api.Assertions.assertThat;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.policy.AutonomyRules;
import edu.neu.strata.healer.policy.PolicyLoader;
import edu.neu.strata.healer.policy.RiskScorer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class TrustServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, clock);
    private final TrustService trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, clock);

    @Test
    void anActionThatNeverRanHasAnEmptyRecord() {
        assertThat(trust.get(ActionType.RESTART)).isEqualTo(TrustRecord.empty(ActionType.RESTART));
        assertThat(trust.history(ActionType.ROLLBACK).failureRate()).isZero();
        assertThat(trust.all()).extracting(TrustRecord::action).containsExactly(ActionType.values());
    }

    @Test
    void countsSuccessesAndFailures() {
        trust.recordSuccess(ActionType.RESTART, 1L);
        trust.recordSuccess(ActionType.RESTART, 2L);
        TrustRecord last = trust.recordFailure(ActionType.RESTART, 3L);

        // the failure ends the run of successes and demotes restart one level (earned autonomy)
        assertThat(last).isEqualTo(new TrustRecord(ActionType.RESTART, 2, 1, 0, 1, NOW));
        assertThat(trust.get(ActionType.RESTART)).isEqualTo(last);
        assertThat(trust.get(ActionType.SCALE_OUT).runs()).isZero();
        assertThat(trust.all()).extracting(TrustRecord::runs).containsExactly(3, 0, 0);
    }

    @Test
    void writesEachChangeToTheAuditLog() {
        trust.recordSuccess(ActionType.RESTART, 7L);
        trust.recordFailure(ActionType.RESTART, null);

        List<AuditEntry> entries = audit.latest(10);  // newest first
        assertThat(entries).extracting(AuditEntry::eventType).containsExactly(
                AuditEventType.AUTONOMY_CHANGE, AuditEventType.TRUST_UPDATE, AuditEventType.TRUST_UPDATE);
        assertThat(entries.get(2).incidentId()).isEqualTo(7L);
        assertThat(entries.get(2).payload()).isEqualTo("{\"action\":\"RESTART\",\"failures\":0,\"levelShift\":0,"
                + "\"outcome\":\"SUCCESS\",\"successStreak\":1,\"successes\":1}");
        assertThat(entries.get(1).payload()).isEqualTo("{\"action\":\"RESTART\",\"failures\":1,\"levelShift\":1,"
                + "\"outcome\":\"FAILURE\",\"successStreak\":0,\"successes\":1}");
        assertThat(entries.get(0).payload()).isEqualTo("{\"action\":\"RESTART\",\"change\":\"DEMOTED\",\"fromShift\":0,"
                + "\"reason\":\"the action failed or the service did not recover\",\"toShift\":1}");
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    void failuresRaiseTheRiskScore() throws Exception {
        RiskScorer scorer = new RiskScorer(new PolicyLoader().loadDefault());
        Diagnosis restart = new Diagnosis("container-crash", ActionType.RESTART, "order-service", 0.95);
        int before = scorer.assess(restart, trust.history(ActionType.RESTART)).score();

        trust.recordFailure(ActionType.RESTART, null);
        trust.recordFailure(ActionType.RESTART, null);

        int after = scorer.assess(restart, trust.history(ActionType.RESTART)).score();
        assertThat(after).isGreaterThan(before);
        assertThat(scorer.assess(restart, trust.history(ActionType.RESTART)).level())
                .isNotEqualTo(AutonomyLevel.L0_AUTO);
    }

    @Test
    void concurrentUpdatesNeverLoseACount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                boolean success = i % 4 != 0;
                runs.add(pool.submit(() -> success
                        ? trust.recordSuccess(ActionType.RESTART, null)
                        : trust.recordFailure(ActionType.RESTART, null)));
            }
            for (Future<?> run : runs) {
                run.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(trust.get(ActionType.RESTART)).extracting(TrustRecord::successes, TrustRecord::failures)
                .containsExactly(30, 10);
        assertThat(audit.latest(500)).filteredOn(e -> e.eventType() == AuditEventType.TRUST_UPDATE).hasSize(40);
        assertThat(audit.verify().valid()).isTrue();
    }

    private TrustService trustWith(AutonomyRules rules) {
        return new TrustService(new TrustRepository(jdbc), db.transactions, audit, clock, rules);
    }

    private List<AuditEntry> autonomyChanges() {
        return audit.latest(500).stream().filter(e -> e.eventType() == AuditEventType.AUTONOMY_CHANGE).toList();
    }

    @Test
    void aRunOfSuccessesPromotesTheActionOnceAndIsAudited() {
        for (long i = 1; i <= 4; i++) {
            assertThat(trust.recordSuccess(ActionType.RESTART, i).levelShift()).isZero();
        }
        TrustRecord fifth = trust.recordSuccess(ActionType.RESTART, 5L);

        assertThat(fifth.levelShift()).isEqualTo(-1);
        assertThat(fifth.successStreak()).isZero();
        assertThat(trust.history(ActionType.RESTART).levelShift()).isEqualTo(-1);
        assertThat(autonomyChanges()).singleElement().satisfies(e -> {
            assertThat(e.incidentId()).isEqualTo(5L);
            assertThat(e.payload()).contains("\"change\":\"PROMOTED\"", "\"toShift\":-1", "5 successes in a row");
        });

        // maxPromotion is 1: five more successes keep it where it is, and write no new change
        for (long i = 6; i <= 10; i++) {
            trust.recordSuccess(ActionType.RESTART, i);
        }
        assertThat(trust.get(ActionType.RESTART).levelShift()).isEqualTo(-1);
        assertThat(autonomyChanges()).hasSize(1);
    }

    @Test
    void failuresDemoteUpToTheCapAndSuccessesEarnItBack() {
        trust.recordFailure(ActionType.RESTART, 1L);
        trust.recordFailure(ActionType.RESTART, 2L);
        trust.recordFailure(ActionType.RESTART, 3L);
        assertThat(trust.get(ActionType.RESTART).levelShift()).isEqualTo(2);  // maxDemotion

        for (int i = 0; i < 5; i++) {
            trust.recordSuccess(ActionType.RESTART, null);
        }
        assertThat(trust.get(ActionType.RESTART).levelShift()).isEqualTo(1);
        assertThat(autonomyChanges()).extracting(AuditEntry::payload)
                .anyMatch(p -> p.contains("PROMOTED") && p.contains("\"fromShift\":2"));
    }

    @Test
    void standingIsKeptInTheDatabase() {
        trust.recordFailure(ActionType.SCALE_OUT, null);
        trust.recordSuccess(ActionType.SCALE_OUT, null);

        TrustService restarted = new TrustService(new TrustRepository(jdbc), db.transactions, audit, clock);
        assertThat(restarted.get(ActionType.SCALE_OUT))
                .extracting(TrustRecord::successStreak, TrustRecord::levelShift).containsExactly(1, 1);
    }

    @Test
    void policyRulesAreUsed() {
        TrustService quick = trustWith(new AutonomyRules(2, 1, 0, 0));
        quick.recordSuccess(ActionType.RESTART, null);
        quick.recordFailure(ActionType.RESTART, null);  // demotion off: only resets the run
        quick.recordSuccess(ActionType.RESTART, null);

        assertThat(quick.recordSuccess(ActionType.RESTART, null).levelShift()).isEqualTo(-1);
    }

    @Test
    void repeatedFailuresTakeRestartOutOfAutomaticMode() {
        RiskScorer scorer = new RiskScorer(new PolicyLoader().loadDefault());
        Diagnosis restart = new Diagnosis("service-down-restart", ActionType.RESTART, "order-service", 0.9);
        assertThat(scorer.assess(restart, trust.history(ActionType.RESTART)).level()).isEqualTo(AutonomyLevel.L0_AUTO);

        trust.recordSuccess(ActionType.RESTART, null);
        trust.recordFailure(ActionType.RESTART, null);
        trust.recordFailure(ActionType.RESTART, null);

        // The score itself rises to L1 (2 of 3 runs failed), and two demotions add two levels: L3, human only
        assertThat(trust.get(ActionType.RESTART).levelShift()).isEqualTo(2);
        assertThat(scorer.assess(restart, trust.history(ActionType.RESTART)).level())
                .isEqualTo(AutonomyLevel.L3_HUMAN_ONLY);
    }
}
