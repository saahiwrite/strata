package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Scores a proposed action from 0 (safe) to 100 (risky) and maps the score to an autonomy
 * level (FR5, FR6).
 *
 * <p>Each of the five factors gives a value from 0.0 to 1.0, which is multiplied by its weight
 * from the policy and rounded; the score is the sum of those contributions, so the factors in
 * the {@link RiskAssessment} always add up to its score (handy in the audit log). The level is
 * the one the thresholds give, raised to the action's {@code minLevel} if that is stricter.
 * An action the policy doesn't list scores every action factor as 1.0 and always gets L3.
 */
public final class RiskScorer {

    private final Policy policy;

    public RiskScorer(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Scores the diagnosis' action in the policy's own environment. */
    public RiskAssessment assess(Diagnosis diagnosis, ActionHistory history) {
        return assess(diagnosis, history, policy.environment());
    }

    public RiskAssessment assess(Diagnosis diagnosis, ActionHistory history, String environment) {
        Objects.requireNonNull(diagnosis, "diagnosis");
        ActionHistory past = history == null ? ActionHistory.NONE : history;
        ActionType type = diagnosis.proposedAction();
        ActionPolicy action = policy.action(type);

        Map<String, Integer> factors = new LinkedHashMap<>();
        int score = 0;
        score += add(factors, RiskFactor.BLAST_RADIUS, action == null ? 1.0 : action.blastRadius());
        score += add(factors, RiskFactor.REVERSIBILITY, action == null ? 1.0 : action.reversibility());
        score += add(factors, RiskFactor.ENVIRONMENT, policy.environmentRisk(environment));
        score += add(factors, RiskFactor.DIAGNOSIS_CONFIDENCE, 1.0 - diagnosis.confidence());
        score += add(factors, RiskFactor.PAST_FAILURES, past.failureRate());
        score = Math.min(score, RiskAssessment.MAX_SCORE);

        AutonomyLevel level = action == null
                ? AutonomyLevel.L3_HUMAN_ONLY
                : policy.thresholds().levelFor(score).stricterOf(action.minLevel());
        return new RiskAssessment(score, level, factors);
    }

    private int add(Map<String, Integer> factors, RiskFactor factor, double value) {
        int contribution = (int) Math.round(policy.weight(factor) * value);
        factors.put(factor.key(), contribution);
        return contribution;
    }
}
