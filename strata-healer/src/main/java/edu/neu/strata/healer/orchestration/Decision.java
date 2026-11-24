package edu.neu.strata.healer.orchestration;

import edu.neu.strata.healer.model.DecisionOutcome;

// A saved decision and where it is now. Only IncidentLifecycle changes the outcome.
public class Decision {

    private final long id;
    private final long incidentId;
    private DecisionOutcome outcome;

    Decision(long id, long incidentId, DecisionOutcome outcome) {
        this.id = id;
        this.incidentId = incidentId;
        this.outcome = outcome;
    }

    public long id() {
        return id;
    }

    public long incidentId() {
        return incidentId;
    }

    public DecisionOutcome outcome() {
        return outcome;
    }

    void setOutcome(DecisionOutcome outcome) {
        this.outcome = outcome;
    }
}
