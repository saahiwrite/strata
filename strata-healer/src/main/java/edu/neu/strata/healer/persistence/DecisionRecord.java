package edu.neu.strata.healer.persistence;

import com.fasterxml.jackson.annotation.JsonRawValue;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.DecisionOutcome;
import java.time.Instant;

/**
 * One row of {@code decisions}: a proposed action, whether the allow-list let it through,
 * its risk and how it ended. The enum fields are stored as their names, so the API and the
 * dashboard see the same text as before, but a typo no longer compiles (#62).
 *
 * @param riskFactors each factor's contribution as JSON (e.g. {@code {"blastRadius":5}}), or null
 *                    when the action was rejected before scoring; written into API responses as an object
 * @param autonomyLevel the level the action was given, or null when it was rejected before scoring
 */
public record DecisionRecord(
        long id,
        long incidentId,
        String ruleId,
        ActionType action,
        String target,
        double confidence,
        boolean allowed,
        String rejectionReason,
        Integer riskScore,
        @JsonRawValue String riskFactors,
        AutonomyLevel autonomyLevel,
        DecisionOutcome outcome,
        Instant decidedAt) {
}
