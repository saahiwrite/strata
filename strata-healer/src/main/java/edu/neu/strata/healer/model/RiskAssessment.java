package edu.neu.strata.healer.model;

import java.util.Map;
import java.util.Objects;

/**
 * The risk scorer's verdict for one proposed action (FR5, FR6).
 *
 * @param score   total risk from 0 (safe) to 100 (dangerous)
 * @param level   the autonomy level the score maps to
 * @param factors each factor's contribution to the score, e.g. {@code blastRadius -> 10}; kept for the audit log
 */
public record RiskAssessment(int score, AutonomyLevel level, Map<String, Integer> factors) {

    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;

    public RiskAssessment {
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new IllegalArgumentException("score must be between 0 and 100, was " + score);
        }
        Objects.requireNonNull(level, "level");
        factors = factors == null ? Map.of() : Map.copyOf(factors);
    }
}
