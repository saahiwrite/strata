package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.AutonomyLevel;

/**
 * Earned autonomy (FR10): how an action's autonomy level moves with its results, from the
 * {@code autonomy} section of {@code policy.yaml}.
 *
 * <p>Each action carries a <em>level shift</em>: 0 means "the level its risk score gives", -1 means one
 * level less cautious (promoted), +1 one level more cautious (demoted). A run of {@code promoteAfter}
 * successes in a row moves it one step down (at most {@code maxPromotion} below 0); every failure moves
 * it {@code demoteOnFailure} steps up (at most {@code maxDemotion}) and starts the run again.
 *
 * <p>Two limits always hold, whatever the shift: a promotion never takes an action below its
 * {@code minLevel}, and never takes anything out of L3, so an L3 action is never run automatically.
 *
 * @param promoteAfter    successes in a row that earn one level less caution (at least 1)
 * @param maxPromotion    most levels an action may be promoted (0 turns promotion off)
 * @param demoteOnFailure levels more caution after each failure (0 turns demotion off)
 * @param maxDemotion     most levels an action may be demoted
 */
public record AutonomyRules(int promoteAfter, int maxPromotion, int demoteOnFailure, int maxDemotion) {

    /** Used when the policy has no {@code autonomy} section. */
    public static final AutonomyRules DEFAULT = new AutonomyRules(5, 1, 1, 2);

    /** An action's place in earned autonomy. */
    public record Standing(int successStreak, int levelShift) {

        public static final Standing START = new Standing(0, 0);
    }

    public AutonomyRules {
        if (promoteAfter < 1) {
            throw new IllegalArgumentException("promoteAfter must be at least 1, was " + promoteAfter);
        }
        int levels = AutonomyLevel.values().length - 1;
        for (int value : new int[] {maxPromotion, demoteOnFailure, maxDemotion}) {
            if (value < 0 || value > levels) {
                throw new IllegalArgumentException("autonomy limits must be between 0 and " + levels + ", was " + value);
            }
        }
    }

    /** The standing after a run that recovered the service. */
    public Standing afterSuccess(Standing before) {
        int streak = before.successStreak() + 1;
        if (streak >= promoteAfter && before.levelShift() > -maxPromotion) {
            return new Standing(0, before.levelShift() - 1);  // earned a step; the next one needs a new run
        }
        return new Standing(streak, before.levelShift());
    }

    /** The standing after a run that failed or didn't recover the service. */
    public Standing afterFailure(Standing before) {
        return new Standing(0, Math.min(before.levelShift() + demoteOnFailure, maxDemotion));
    }

    /**
     * The level after the shift. The caller still applies the action's {@code minLevel} afterwards.
     *
     * @param scored the level the risk score gives
     * @param shift  the action's level shift
     */
    public AutonomyLevel apply(AutonomyLevel scored, int shift) {
        if (scored == AutonomyLevel.L3_HUMAN_ONLY && shift < 0) {
            return scored;  // L3 is never earned away
        }
        int index = Math.clamp(scored.ordinal() + shift, 0, AutonomyLevel.values().length - 1);
        return AutonomyLevel.values()[index];
    }
}
