package edu.neu.strata.healer.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Counts what the prompt-injection defense did, from the audit log alone, so the numbers survive
 * restarts and can't disagree with the record. The benchmark uses them for the injection block rate.
 */
@Service
public class InjectionReport {

    /**
     * @param incidentsFlagged          incidents whose logs had instruction-like lines (withheld, LLM not asked)
     * @param linesWithheld             how many log lines were withheld in total
     * @param signals                   how often each signal fired, by id (see {@code LogScreen})
     * @param proposalsBlocked          proposed actions the allow-list rejected (any incident)
     * @param actionsOnFlaggedIncidents actions that still ran on a flagged incident (from a rule; never from the LLM)
     */
    public record Stats(long incidentsFlagged, long linesWithheld, Map<String, Long> signals,
                        long proposalsBlocked, long actionsOnFlaggedIncidents) {
    }

    private final AuditLogService audit;
    private final ObjectMapper json = new ObjectMapper();

    public InjectionReport(AuditLogService audit) {
        this.audit = audit;
    }

    public Stats stats() {
        List<AuditEntry> flagged = audit.ofType(AuditEventType.INJECTION_SUSPECTED);
        Set<Long> flaggedIncidents = flagged.stream().map(AuditEntry::incidentId).collect(Collectors.toSet());

        long lines = 0;
        Map<String, Long> signals = new TreeMap<>();
        for (AuditEntry entry : flagged) {
            JsonNode payload = read(entry);
            lines += payload.path("linesWithheld").asLong(0);
            payload.path("signals").forEach(signal -> signals.merge(signal.asText(), 1L, Long::sum));
        }

        long blocked = audit.ofType(AuditEventType.VALIDATION).stream()
                .filter(entry -> !read(entry).path("allowed").asBoolean(true))
                .count();
        long actions = audit.ofType(AuditEventType.EXECUTION).stream()
                .filter(entry -> flaggedIncidents.contains(entry.incidentId()))
                .filter(entry -> !read(entry).has("skipped"))
                .count();

        return new Stats(flaggedIncidents.size(), lines, new LinkedHashMap<>(signals), blocked, actions);
    }

    private JsonNode read(AuditEntry entry) {
        try {
            return json.readTree(entry.payload());
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
