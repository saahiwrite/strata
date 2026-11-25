package edu.neu.strata.healer.model;

/**
 * What happened to a decision. Stored in {@code decisions.outcome} by name, and these are exactly
 * the values that column's check constraint accepts ({@code V1__initial_schema.sql}), so adding one
 * here needs a migration too; {@code DecisionRoundTripTest} fails until it has one.
 *
 * <p>{@code IncidentLifecycle} decides which outcome may follow which.
 */
public enum DecisionOutcome {
    /** Scored and about to run, or waiting for the orchestrator's next step. */
    PENDING,
    /** The allow-list refused it before scoring (FR4). */
    REJECTED,
    /** L2: waiting for an engineer to approve it (FR8). */
    AWAITING_APPROVAL,
    /** L3, a denial or a timeout: a human takes over and nothing runs. */
    HANDED_TO_HUMAN,
    /** The action ran; recovery is not checked yet. */
    EXECUTED,
    /** The action ran and the service recovered. */
    RECOVERED,
    /** The action failed, or ran without the service recovering. */
    FAILED
}
