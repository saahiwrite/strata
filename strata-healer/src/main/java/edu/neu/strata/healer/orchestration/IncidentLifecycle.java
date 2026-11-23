package edu.neu.strata.healer.orchestration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentService;
import edu.neu.strata.healer.detection.IncidentStatus;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.DecisionOutcome;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;
import edu.neu.strata.healer.persistence.DecisionStore;

// The only place that changes an incident's status or a decision's outcome.
// Illegal moves are rejected here, and the closing ones are written to the audit log.
@Component
public class IncidentLifecycle {

    // incident: OPEN -> RESOLVED or ESCALATED, and nothing after that
    // decision: where each outcome is allowed to go next (REJECTED and PENDING are the starting points)
    private static final Map<DecisionOutcome, Set<DecisionOutcome>> NEXT = new EnumMap<>(DecisionOutcome.class);

    static {
        NEXT.put(DecisionOutcome.PENDING, Set.of(DecisionOutcome.AWAITING_APPROVAL, DecisionOutcome.HANDED_TO_HUMAN,
                DecisionOutcome.EXECUTED, DecisionOutcome.FAILED));
        NEXT.put(DecisionOutcome.AWAITING_APPROVAL, Set.of(DecisionOutcome.EXECUTED, DecisionOutcome.HANDED_TO_HUMAN,
                DecisionOutcome.FAILED));
        NEXT.put(DecisionOutcome.EXECUTED, Set.of(DecisionOutcome.RECOVERED, DecisionOutcome.FAILED));
    }

    private final IncidentService incidents;
    private final DecisionStore decisions;
    private final AuditLogService audit;

    public IncidentLifecycle(IncidentService incidents, DecisionStore decisions, AuditLogService audit) {
        this.incidents = incidents;
        this.decisions = decisions;
        this.audit = audit;
    }

    // Empty when the same alert already has an open incident
    public Optional<Incident> openIfNew(Alert alert) {
        if (incidents.findOpen(alert.service(), alert.alertName()).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(incidents.open(alert));
    }

    public void begin(Incident incident) {
        Alert alert = incident.alert();
        audit.append(AuditEventType.INCIDENT_OPENED, incident.id(), Map.of(
                "alertName", alert.alertName(),
                "service", alert.service(),
                "container", String.valueOf(alert.container())));
    }

    public Decision propose(Incident incident, Diagnosis diagnosis, RiskAssessment risk) {
        long id = decisions.insert(incident.id(), diagnosis, null, risk, DecisionOutcome.PENDING);
        return new Decision(id, incident.id(), DecisionOutcome.PENDING);
    }

    // The validator said no: the decision is stored as rejected and the incident goes to a human
    public void reject(Incident incident, Diagnosis diagnosis, String reason) {
        decisions.insert(incident.id(), diagnosis, reason, null, DecisionOutcome.REJECTED);
        escalate(incident, "rejected: " + reason);
    }

    public void move(Decision decision, DecisionOutcome next) {
        Set<DecisionOutcome> allowed = NEXT.getOrDefault(decision.outcome(), Set.of());
        if (!allowed.contains(next)) {
            throw new IllegalStateException("decision " + decision.id() + " can't go from "
                    + decision.outcome() + " to " + next);
        }
        decisions.updateOutcome(decision.id(), next);
        decision.setOutcome(next);
    }

    public void awaitApproval(Decision decision) {
        move(decision, DecisionOutcome.AWAITING_APPROVAL);
        audit.append(AuditEventType.APPROVAL, decision.incidentId(), Map.of("status", "waiting for approval"));
    }

    public void handToHuman(Incident incident, Decision decision, String reason) {
        move(decision, DecisionOutcome.HANDED_TO_HUMAN);
        escalate(incident, reason);
    }

    public void resolve(Incident incident) {
        requireOpen(incident);
        incidents.resolve(incident.id());
        audit.append(AuditEventType.INCIDENT_CLOSED, incident.id(), Map.of("status", IncidentStatus.RESOLVED.name()));
    }

    public void escalate(Incident incident, String reason) {
        requireOpen(incident);
        incidents.escalate(incident.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", IncidentStatus.ESCALATED.name());
        details.put("reason", reason);
        audit.append(AuditEventType.INCIDENT_CLOSED, incident.id(), details);
    }

    // For the safety net around a whole run: the incident may already have been closed
    public void escalateIfOpen(Incident incident, String reason) {
        if (isOpen(incident)) {
            escalate(incident, reason);
        }
    }

    private void requireOpen(Incident incident) {
        if (!isOpen(incident)) {
            throw new IllegalStateException("incident " + incident.id() + " is not open any more");
        }
    }

    private boolean isOpen(Incident incident) {
        return incidents.status(incident.id()).filter(s -> s == IncidentStatus.OPEN).isPresent();
    }
}
