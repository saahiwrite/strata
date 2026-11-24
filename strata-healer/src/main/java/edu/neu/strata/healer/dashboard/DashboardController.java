package edu.neu.strata.healer.dashboard;

import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;
import edu.neu.strata.healer.persistence.IncidentRepository;
import edu.neu.strata.healer.trust.TrustService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The engineer's dashboard (FR12), rendered with Thymeleaf. It only reads, through the same
 * services as the JSON API; there is no business logic here. Approve and Deny are buttons that
 * call the approval API ({@code POST /api/approvals/{decisionId}/approve|deny}) from the browser.
 *
 * <ul>
 *   <li>{@code GET /dashboard?status=OPEN}: pending approvals, incidents newest first, action
 *       records and the audit chain status</li>
 *   <li>{@code GET /dashboard/incidents/{id}}: one incident with its decisions and audit entries</li>
 * </ul>
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    static final int INCIDENT_LIMIT = 50;
    static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    private static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "ESCALATED");

    private final IncidentRepository incidents;
    private final TrustService trust;
    private final AuditLogService audit;

    public DashboardController(IncidentRepository incidents, TrustService trust, AuditLogService audit) {
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @ModelAttribute("fmt")
    public DashboardFormat format() {
        return DashboardFormat.INSTANCE;
    }

    @GetMapping
    public String overview(@RequestParam(required = false) String status, Model model) {
        String wanted = normalise(status);
        List<IncidentRow> rows = new ArrayList<>();
        for (IncidentRecord incident : incidents.findLatest(wanted, INCIDENT_LIMIT)) {
            rows.add(new IncidentRow(incident, latest(incidents.findDecisions(incident.id()))));
        }
        model.addAttribute("status", wanted);
        model.addAttribute("rows", rows);
        model.addAttribute("pending", pendingApprovals());
        model.addAttribute("trust", trust.all());
        model.addAttribute("chain", audit.verify());
        return "dashboard/index";
    }

    @GetMapping("/incidents/{id}")
    public String incident(@PathVariable long id, Model model) {
        IncidentRecord incident = incidents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no incident " + id));
        List<DecisionRecord> decisions = incidents.findDecisions(id);
        model.addAttribute("incident", incident);
        model.addAttribute("decisions", decisions);
        model.addAttribute("factors", decisions.stream().map(d -> RiskFactors.parse(d.riskFactors())).toList());
        model.addAttribute("audit", audit.forIncident(id));
        return "dashboard/incident";
    }

    /** Decisions of open incidents that wait for an engineer, oldest first. */
    private List<PendingApprovalView> pendingApprovals() {
        List<PendingApprovalView> pending = new ArrayList<>();
        for (IncidentRecord incident : incidents.findLatest("OPEN", INCIDENT_LIMIT)) {
            for (DecisionRecord decision : incidents.findDecisions(incident.id())) {
                if (AWAITING_APPROVAL.equals(decision.outcome())) {
                    pending.add(new PendingApprovalView(incident, decision, RiskFactors.parse(decision.riskFactors())));
                }
            }
        }
        pending.sort(Comparator.comparing((PendingApprovalView p) -> p.decision().decidedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));
        return pending;
    }

    /** The repository returns decisions oldest first, so the latest is the last one. */
    private static DecisionRecord latest(List<DecisionRecord> decisions) {
        return decisions.isEmpty() ? null : decisions.get(decisions.size() - 1);
    }

    private static String normalise(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String wanted = status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(wanted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be OPEN, RESOLVED or ESCALATED");
        }
        return wanted;
    }
}
