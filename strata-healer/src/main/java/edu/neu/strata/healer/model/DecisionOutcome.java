package edu.neu.strata.healer.model;

// What happened to a decision. These are the values the decisions.outcome column accepts.
public enum DecisionOutcome {
    PENDING,
    REJECTED,
    AWAITING_APPROVAL,
    HANDED_TO_HUMAN,
    EXECUTED,
    RECOVERED,
    FAILED
}
