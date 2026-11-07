package edu.neu.strata.healer.model;

/**
 * How much freedom STRATA has for one proposed action, from the risk score (FR6).
 * Declared from least to most restrictive, so {@link #compareTo} orders them by caution.
 */
public enum AutonomyLevel {
    /** Run automatically. */
    L0_AUTO,
    /** Run automatically, keeping a way to undo it. */
    L1_AUTO_UNDO,
    /** Show a preview and wait for an engineer to approve it. */
    L2_APPROVAL,
    /** Never run by STRATA; a human handles it. */
    L3_HUMAN_ONLY;

    /** True when STRATA may run the action without asking anyone (L0 and L1 only). */
    public boolean runsAutomatically() {
        return this == L0_AUTO || this == L1_AUTO_UNDO;
    }

    /** The more cautious of the two levels. */
    public AutonomyLevel stricterOf(AutonomyLevel other) {
        return other != null && other.compareTo(this) > 0 ? other : this;
    }
}
