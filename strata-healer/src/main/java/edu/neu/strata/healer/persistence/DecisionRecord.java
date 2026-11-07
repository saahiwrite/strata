package edu.neu.strata.healer.persistence;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * One row of {@code decisions}: a proposed action, whether the allow-list let it through,
 * its risk and how it ended.
 *
 * @param riskFactors each factor's contribution as JSON (e.g. {@code {"blastRadius":5}}), or null
 *                    when the action was rejected before scoring; written into API responses as an object
 */
public record DecisionRecord(
        long id,
        long incidentId,
        String ruleId,
        String action,
        String target,
        double confidence,
        boolean allowed,
        String rejectionReason,
        Integer riskScore,
        @JsonRawValue String riskFactors,
        String autonomyLevel,
        String outcome,
        Instant decidedAt) {
}
