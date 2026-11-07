package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.AutonomyLevel;
import java.util.Objects;

/**
 * The policy's risk values for one action type.
 *
 * @param blastRadius   0.0-1.0, how much of the system the action touches
 * @param reversibility 0.0-1.0, how hard the action is to undo (1.0 = can't be undone)
 * @param minLevel      the least cautious level the action may get, whatever its score
 */
public record ActionPolicy(double blastRadius, double reversibility, AutonomyLevel minLevel) {

    public ActionPolicy {
        Policy.requireUnit(blastRadius, "blastRadius");
        Policy.requireUnit(reversibility, "reversibility");
        Objects.requireNonNull(minLevel, "minLevel");
    }
}
