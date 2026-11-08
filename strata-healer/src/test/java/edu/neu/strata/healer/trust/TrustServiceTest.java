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

        assertThat(last).isEqualTo(new TrustRecord(ActionType.RESTART, 2, 1, NOW));
        assertThat(trust.get(ActionType.RESTART)).isEqualTo(last);
        assertThat(trust.get(ActionType.SCALE_OUT).runs()).isZero();
        assertThat(trust.all()).extracting(TrustRecord::runs).containsExactly(3, 0, 0);
    }

    @Test
    void writesEachChangeToTheAuditLog() {
        trust.recordSuccess(ActionType.RESTART, 7L);
        trust.recordFailure(ActionType.RESTART, null);

        List<AuditEntry> entries = audit.latest(10);
        assertThat(entries).extracting(AuditEntry::eventType).containsOnly(AuditEventType.TRUST_UPDATE);
        assertThat(entries.get(1).incidentId()).isEqualTo(7L);
        assertThat(entries.get(1).payload())
                .isEqualTo("{\"action\":\"RESTART\",\"failures\":0,\"outcome\":\"SUCCESS\",\"successes\":1}");
        assertThat(entries.get(0).payload())
                .isEqualTo("{\"action\":\"RESTART\",\"failures\":1,\"outcome\":\"FAILURE\",\"successes\":1}");
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
        assertThat(audit.verify().entries()).isEqualTo(40);
        assertThat(audit.verify().valid()).isTrue();
    }
}
