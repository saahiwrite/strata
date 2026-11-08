package edu.neu.strata.healer.api;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.audit.ChainVerification;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit log API (FR11). There is deliberately no way to write or delete through it.
 *
 * <ul>
 *   <li>{@code GET /api/audit?limit=100}: the newest entries, newest first</li>
 *   <li>{@code GET /api/audit?incidentId=7}: one incident's entries, oldest first</li>
 *   <li>{@code GET /api/audit/verify}: re-checks the whole hash chain. Always 200; the body's
 *       {@code valid} says whether it held, and {@code brokenAt} names the first bad entry.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    static final int DEFAULT_LIMIT = 100;

    private final AuditLogService audit;

    public AuditController(AuditLogService audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping
    public List<AuditEntry> list(@RequestParam(required = false) Long incidentId,
                                 @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return incidentId != null ? audit.forIncident(incidentId) : audit.latest(limit);
    }

    @GetMapping("/verify")
    public ChainVerification verify() {
        return audit.verify();
    }
}
