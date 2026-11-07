package edu.neu.strata.healer.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.TestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditLogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30.123456789Z");

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final AuditLogService audit = new AuditLogService(
            new AuditLogRepository(jdbc), db.transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void appendsAChainThatSurvivesTheDatabase() {
        AuditEntry first = audit.append(AuditEventType.INCIDENT_OPENED, 1L, Map.of("service", "order-service"));
        AuditEntry second = audit.append(AuditEventType.RISK_ASSESSMENT, 1L,
                new RiskAssessment(9, AutonomyLevel.L0_AUTO, Map.of("blastRadius", 5, "reversibility", 3)));
        audit.append(AuditEventType.TRUST_UPDATE, null, null);

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.prevHash()).isEqualTo(first.hash());
        assertThat(audit.latest(10)).hasSize(3).first().satisfies(e -> assertThat(e.seq()).isEqualTo(3));
        assertThat(audit.latest(10).get(2)).isEqualTo(first);

        ChainVerification result = audit.verify();
        assertThat(result.valid()).isTrue();
        assertThat(result.entries()).isEqualTo(3);
    }

    @Test
    void writesDetailsAsCanonicalJson() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("target", "order-service");
        details.put("action", "RESTART");

        AuditEntry entry = audit.append(AuditEventType.DIAGNOSIS, 4L, details);
        AuditEntry scored = audit.append(AuditEventType.RISK_ASSESSMENT, 4L,
                new RiskAssessment(9, AutonomyLevel.L0_AUTO, Map.of("reversibility", 3, "blastRadius", 5)));

        assertThat(entry.payload()).isEqualTo("{\"action\":\"RESTART\",\"target\":\"order-service\"}");
        assertThat(scored.payload())
                .isEqualTo("{\"factors\":{\"blastRadius\":5,\"reversibility\":3},\"level\":\"L0_AUTO\",\"score\":9}");
        assertThat(audit.append(AuditEventType.TRUST_UPDATE, null, null).payload()).isEqualTo("{}");
    }

    @Test
    void listsAnIncidentsEntries() {
        audit.append(AuditEventType.INCIDENT_OPENED, 1L, Map.of());
        audit.append(AuditEventType.INCIDENT_OPENED, 2L, Map.of());
        audit.append(AuditEventType.INCIDENT_CLOSED, 1L, Map.of("status", "RESOLVED"));

        assertThat(audit.forIncident(1L)).extracting(AuditEntry::eventType)
                .containsExactly(AuditEventType.INCIDENT_OPENED, AuditEventType.INCIDENT_CLOSED);
        assertThat(audit.forIncident(3L)).isEmpty();
    }

    @Test
    void latestIsClamped() {
        for (int i = 0; i < 3; i++) {
            audit.append(AuditEventType.DIAGNOSIS, null, Map.of("n", i));
        }
        assertThat(audit.latest(0)).hasSize(1);
        assertThat(audit.latest(-5)).hasSize(1);
        assertThat(audit.latest(Integer.MAX_VALUE)).hasSize(3);
    }

    @Test
    void detectsAnEntryEditedInTheDatabase() {
        audit.append(AuditEventType.DIAGNOSIS, 1L, Map.of("action", "RESTART"));
        audit.append(AuditEventType.VALIDATION, 1L, Map.of("allowed", false));
        audit.append(AuditEventType.EXECUTION, 1L, Map.of("success", true));

        jdbc.sql("UPDATE audit_log SET payload = '{\"allowed\":true}' WHERE seq = 2").update();

        ChainVerification result = audit.verify();
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAt()).isEqualTo(2);
    }

    @Test
    void detectsAnEntryDeletedFromTheDatabase() {
        for (int i = 0; i < 4; i++) {
            audit.append(AuditEventType.DIAGNOSIS, null, Map.of("n", i));
        }
        jdbc.sql("DELETE FROM audit_log WHERE seq = 2").update();

        assertThat(audit.verify().brokenAt()).isEqualTo(3);
    }

    @Test
    void theDatabaseRefusesAForkedChain() {
        AuditEntry first = audit.append(AuditEventType.DIAGNOSIS, null, Map.of());
        AuditEntry fork = HashChain.next(null, NOW, AuditEventType.EXECUTION, null, "{}");

        assertThatThrownBy(() -> new AuditLogRepository(jdbc).insert(fork))
                .as("a second entry 1 / a second child of the genesis hash")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(audit.verify().headHash()).isEqualTo(first.hash());
    }

    @Test
    void concurrentAppendsStayInOneChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<AuditEntry>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                futures.add(pool.submit(() -> audit.append(AuditEventType.DIAGNOSIS, null, Map.of("n", n))));
            }
            for (Future<AuditEntry> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ChainVerification result = audit.verify();
        assertThat(result.valid()).isTrue();
        assertThat(result.entries()).isEqualTo(40);
    }

    @Test
    void rejectsDetailsThatArentJson() {
        assertThatThrownBy(() -> audit.append(AuditEventType.DIAGNOSIS, null, new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.verify().entries()).isZero();
    }
}
