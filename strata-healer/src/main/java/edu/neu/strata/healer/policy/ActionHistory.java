package edu.neu.strata.healer.policy;

/**
 * How an action went recently, for the past-failures factor. The trust records (TrustService)
 * provide it; {@link #NONE} means the action has no history yet.
 *
 * @param successes recent runs that recovered the service
 * @param failures  recent runs that didn't
 */
public record ActionHistory(int successes, int failures) {

    public static final ActionHistory NONE = new ActionHistory(0, 0);

    public ActionHistory {
        if (successes < 0 || failures < 0) {
            throw new IllegalArgumentException("counts must not be negative, were " + successes + " and " + failures);
        }
    }

    /** Share of recent runs that failed, 0.0 when there are none. */
    public double failureRate() {
        int total = successes + failures;
        return total == 0 ? 0.0 : (double) failures / total;
    }
}
