package edu.neu.strata.healer.api;

import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.persistence.IncidentRecord;
import edu.neu.strata.healer.persistence.IncidentRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only incidents API (FR12, JSON for now; the Phase 3 dashboard reads the same data).
 *
 * <ul>
 *   <li>{@code GET /api/incidents?status=OPEN&limit=50}: newest first</li>
 *   <li>{@code GET /api/incidents/{id}}: one incident with its decisions and audit entries</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "ESCALATED");

    private final IncidentRepository incidents;
    private final AuditLogService audit;

    public IncidentController(IncidentRepository incidents, AuditLogService audit) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping
    public List<IncidentRecord> list(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        String wanted = null;
        if (status != null && !status.isBlank()) {
            wanted = status.strip().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(wanted)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "status must be OPEN, RESOLVED or ESCALATED");
            }
        }
        return incidents.findLatest(wanted, clamp(limit));
    }

    @GetMapping("/{id}")
    public IncidentDetails get(@PathVariable long id) {
        IncidentRecord incident = incidents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no incident " + id));
        return new IncidentDetails(incident, incidents.findDecisions(id), audit.forIncident(id));
    }

    static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
