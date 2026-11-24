package edu.neu.strata.healer.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.TestDatabase;

class InjectionReportTest {

    private final TestDatabase db = new TestDatabase();
    private final AuditLogService audit = new AuditLogService(
            new AuditLogRepository(JdbcClient.create(db.dataSource)), db.transactions, Clock.systemUTC());
    private final InjectionReport report = new InjectionReport(audit);

    @Test
    void emptyLogCountsNothing() {
        assertThat(report.stats()).isEqualTo(new InjectionReport.Stats(0, 0, Map.of(), 0, 0));
    }

    @Test
    void countsFlaggedIncidentsWithheldLinesSignalsAndBlocks() {
        audit.append(AuditEventType.INJECTION_SUSPECTED, 1L, Map.of("linesWithheld", 2,
                "signals", List.of("override-instructions", "shell-command"), "excerpt", "ignore ..."));
        audit.append(AuditEventType.INJECTION_SUSPECTED, 2L, Map.of("linesWithheld", 1,
                "signals", List.of("shell-command"), "excerpt", "docker rm ..."));
        audit.append(AuditEventType.VALIDATION, 3L, Map.of("allowed", false, "reason", "not on the allow-list"));
        audit.append(AuditEventType.VALIDATION, 4L, Map.of("allowed", true, "reason", "ok"));
        // A rule still restarted incident 2 (ServiceDown); a skipped execution doesn't count
        audit.append(AuditEventType.EXECUTION, 2L, Map.of("action", "RESTART", "success", true));
        audit.append(AuditEventType.EXECUTION, 1L, Map.of("skipped", "cooling down"));
        audit.append(AuditEventType.EXECUTION, 4L, Map.of("action", "RESTART", "success", true));

        InjectionReport.Stats stats = report.stats();

        assertThat(stats.incidentsFlagged()).isEqualTo(2);
        assertThat(stats.linesWithheld()).isEqualTo(3);
        assertThat(stats.signals()).containsExactly(
                Map.entry("override-instructions", 1L), Map.entry("shell-command", 2L));
        assertThat(stats.proposalsBlocked()).isEqualTo(1);
        assertThat(stats.actionsOnFlaggedIncidents()).isEqualTo(1);
    }
}
