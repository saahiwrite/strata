package edu.neu.strata.healer.orchestration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.diagnosis.Diagnoser;
import edu.neu.strata.healer.diagnosis.LokiLogClient;
import edu.neu.strata.healer.metrics.HealerMetrics;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.validation.LogScreen;

// Reads the recent logs, screens them, and asks the diagnosers what is wrong
@Component
public class DiagnosisStage {

    private static final Duration LOG_WINDOW = Duration.ofMinutes(5);
    private static final int LOG_LIMIT = 50;

    // diagnosis is empty when nothing could be proposed, and reason then says why
    public record Result(Optional<Diagnosis> diagnosis, String reason) {

        public boolean found() {
            return diagnosis.isPresent();
        }
    }

    private final LokiLogClient logClient;
    private final LogScreen logScreen;
    private final Diagnoser diagnoser;
    private final AuditLogService audit;
    private final HealerMetrics metrics;

    public DiagnosisStage(LokiLogClient logClient, LogScreen logScreen, Diagnoser diagnoser, AuditLogService audit) {
        this(logClient, logScreen, diagnoser, audit, HealerMetrics.none());
    }

    @Autowired
    public DiagnosisStage(LokiLogClient logClient, LogScreen logScreen, Diagnoser diagnoser, AuditLogService audit,
                          HealerMetrics metrics) {
        this.logClient = logClient;
        this.logScreen = logScreen;
        this.diagnoser = diagnoser;
        this.audit = audit;
        this.metrics = metrics;
    }

    public Result diagnose(Incident incident) {
        Alert alert = incident.alert();
        long id = incident.id();
        List<String> logs = logClient.recentLogs(logSource(alert), LOG_WINDOW, LOG_LIMIT);

        // Logs are untrusted (FR4): lines that try to instruct the healer are withheld from every
        // diagnoser, and the LLM isn't asked at all for this incident; only the rules may answer.
        LogScreen.Result screened = logScreen.screen(logs);
        Diagnoser diagnosing = diagnoser;
        if (screened.suspicious()) {
            audit.append(AuditEventType.INJECTION_SUSPECTED, id, Map.of(
                    "linesWithheld", screened.findings().size(),
                    "signals", List.copyOf(screened.signals()),
                    "excerpt", screened.findings().getFirst().excerpt()));
            diagnosing = diagnoser.withoutLlm();
            metrics.injectionSuspected();
        }

        long started = System.nanoTime();
        Optional<Diagnosis> found = diagnosing.diagnose(alert, screened.clean());
        long diagnosisMs = (System.nanoTime() - started) / 1_000_000;  // reported for LLM timing (Phase 1 NFR)

        if (found.isEmpty()) {
            audit.append(AuditEventType.DIAGNOSIS, id, Map.of("result", "no diagnosis", "durationMs", diagnosisMs));
            String reason = screened.suspicious()
                    ? "possible prompt injection in the logs; no rule matched, so the LLM was not asked"
                    : "no rule matched and no LLM diagnosis";
            return new Result(Optional.empty(), reason);
        }

        Diagnosis diagnosis = found.get();
        audit.append(AuditEventType.DIAGNOSIS, id, Map.of(
                "ruleId", diagnosis.ruleId(),
                "source", diagnosis.source().name(),
                "action", diagnosis.proposedAction().name(),
                "target", diagnosis.target(),
                "confidence", diagnosis.confidence(),
                "durationMs", diagnosisMs));
        return new Result(found, "");
    }

    private String logSource(Alert alert) {
        return alert.container() != null ? alert.container() : alert.service();
    }
}
