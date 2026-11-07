package edu.neu.strata.healer.policy;

/**
 * The five factors the risk scorer weighs (FR5). Each {@link #key()} is the name used in
 * {@code policy.yaml} and in {@link edu.neu.strata.healer.model.RiskAssessment#factors()}.
 */
public enum RiskFactor {
    /** How much of the system the action touches. */
    BLAST_RADIUS("blastRadius"),
    /** How hard the action is to undo. */
    REVERSIBILITY("reversibility"),
    /** How sensitive the environment is. */
    ENVIRONMENT("environment"),
    /** How unsure the diagnosis is (1 - confidence). */
    DIAGNOSIS_CONFIDENCE("diagnosisConfidence"),
    /** How often this action failed recently. */
    PAST_FAILURES("pastFailures");

    private final String key;

    RiskFactor(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The factor with this policy key, or null if there is none. */
    public static RiskFactor fromKey(String key) {
        for (RiskFactor factor : values()) {
            if (factor.key.equals(key)) {
                return factor;
            }
        }
        return null;
    }
}
