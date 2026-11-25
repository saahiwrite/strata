package edu.neu.strata.healer.audit;

/** The kinds of decision the healer writes to the audit log (FR11), in the order a healing run produces them. */
public enum AuditEventType {
    /** An alert opened an incident. */
    INCIDENT_OPENED,
    /** Log lines that try to instruct the healer were found and withheld from diagnosis (FR4). */
    INJECTION_SUSPECTED,
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
    UNDO,
    /** The action's trust record changed. */
    TRUST_UPDATE,
    /** An action's earned autonomy changed: it was promoted or demoted a level (FR10). */
    AUTONOMY_CHANGE,
    /** The incident was resolved or escalated. */
    INCIDENT_CLOSED
}
