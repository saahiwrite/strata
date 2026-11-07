package edu.neu.strata.healer.audit;

/** The kinds of decision the healer writes to the audit log (FR11), in the order a healing run produces them. */
public enum AuditEventType {
    /** An alert opened an incident. */
    INCIDENT_OPENED,
    /** The diagnosis stage proposed an action. */
    DIAGNOSIS,
    /** The action validator allowed or rejected the proposed action. */
    VALIDATION,
    /** The risk scorer's score, factors and level. */
    RISK_ASSESSMENT,
    /** An engineer approved or denied an L2 action. */
    APPROVAL,
    /** The action ran (or the Docker call failed). */
    EXECUTION,
    /** The recovery check's result. */
    VERIFICATION,
    /** The action's trust record changed. */
    TRUST_UPDATE,
    /** The incident was resolved or escalated. */
    INCIDENT_CLOSED
}
