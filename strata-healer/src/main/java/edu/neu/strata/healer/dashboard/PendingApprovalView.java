package edu.neu.strata.healer.dashboard;

import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;

import java.util.Map;

/**
 * A decision waiting for an engineer, with what they need to say yes or no: the action, its
 * target, the risk score and each factor's share of it, and the diagnosis that proposed it.
 */
public record PendingApprovalView(IncidentRecord incident, DecisionRecord decision, Map<String, Integer> factors) {
}
