package edu.neu.strata.healer.trust;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.policy.ActionHistory;
import java.time.Instant;
import java.util.Objects;

/**
 * One action type's success record (FR10), as stored in {@code action_trust}.
 *
 * @param action    the action type
 * @param successes runs after which the service recovered
 * @param failures  runs after which it didn't (or the action itself failed)
 * @param updatedAt when the record last changed, or null when the action has never run
 */
public record TrustRecord(ActionType action, int successes, int failures, Instant updatedAt) {

    public TrustRecord {
        Objects.requireNonNull(action, "action");
        if (successes < 0 || failures < 0) {
            throw new IllegalArgumentException("counts must not be negative, were " + successes + " and " + failures);
        }
    }

    /** The record of an action that has never run. */
    public static TrustRecord empty(ActionType action) {
        return new TrustRecord(action, 0, 0, null);
    }

    public int runs() {
        return successes + failures;
    }

    /** Share of runs that recovered the service, 0.0 when there are none. */
    public double successRate() {
        return runs() == 0 ? 0.0 : (double) successes / runs();
    }

    /** The counts in the form the risk scorer's past-failures factor takes. */
    public ActionHistory toHistory() {
        return new ActionHistory(successes, failures);
    }
}
