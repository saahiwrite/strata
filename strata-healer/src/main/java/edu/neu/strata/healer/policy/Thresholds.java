package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.AutonomyLevel;

/**
 * Where each autonomy level starts on the 0-100 score (FR6): scores below {@code l1From} are L0,
 * then L1 up to {@code l2From}, L2 up to {@code l3From}, and L3 from {@code l3From} up.
 */
public record Thresholds(int l1From, int l2From, int l3From) {

    public Thresholds {
        if (l1From < 1 || l1From >= l2From || l2From >= l3From || l3From > 100) {
            throw new IllegalArgumentException(
                    "thresholds must satisfy 1 <= l1From < l2From < l3From <= 100, were "
                            + l1From + ", " + l2From + ", " + l3From);
        }
    }

    public AutonomyLevel levelFor(int score) {
        if (score >= l3From) {
            return AutonomyLevel.L3_HUMAN_ONLY;
        }
        if (score >= l2From) {
            return AutonomyLevel.L2_APPROVAL;
        }
        if (score >= l1From) {
            return AutonomyLevel.L1_AUTO_UNDO;
        }
        return AutonomyLevel.L0_AUTO;
    }
}
