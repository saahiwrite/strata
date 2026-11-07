package edu.neu.strata.healer.api;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;
import java.util.List;

/** {@code GET /api/incidents/{id}}: the incident, its decisions and its audit entries (oldest first). */
public record IncidentDetails(IncidentRecord incident, List<DecisionRecord> decisions, List<AuditEntry> audit) {
}
