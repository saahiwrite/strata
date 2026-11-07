package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.ActionType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The risk policy loaded from {@code policy.yaml}: factor weights, level thresholds,
 * environment risks and per-action risks. Immutable; built by {@link PolicyLoader}.
 *
 * @param environment  the environment this healer runs in
 * @param weights      every factor's weight; the weights add up to 100
 * @param thresholds   where each autonomy level starts
 * @param environments risk (0.0-1.0) of each known environment
 * @param actions      risk values of each action the policy knows
 * @param allowList    the actions and targets STRATA may ever run (FR4)
 */
public record Policy(
        String environment,
        Map<RiskFactor, Integer> weights,
        Thresholds thresholds,
        Map<String, Double> environments,
        Map<ActionType, ActionPolicy> actions,
        AllowList allowList) {

    /** The weights must add up to this, so the score is 0-100. */
    public static final int TOTAL_WEIGHT = 100;

    /** Risk of an environment the policy doesn't list. */
    public static final double UNKNOWN_ENVIRONMENT_RISK = 1.0;

    public Policy {
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        environment = environment.strip();
        Objects.requireNonNull(thresholds, "thresholds");

        Objects.requireNonNull(weights, "weights");
        int total = 0;
        for (RiskFactor factor : RiskFactor.values()) {
            Integer weight = weights.get(factor);
            if (weight == null) {
                throw new IllegalArgumentException("missing weight for " + factor.key());
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight of " + factor.key() + " must not be negative, was " + weight);
            }
            total += weight;
        }
        if (total != TOTAL_WEIGHT) {
            throw new IllegalArgumentException("weights must add up to " + TOTAL_WEIGHT + ", were " + total);
        }
        weights = Map.copyOf(new EnumMap<>(weights));

        environments = environments == null ? Map.of() : Map.copyOf(environments);
        environments.forEach((name, risk) -> requireUnit(risk, "environments." + name));

        actions = actions == null || actions.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(actions));
        allowList = allowList == null ? AllowList.NONE : allowList;
    }

    /** A policy without an allow-list (so nothing may run); handy for scoring-only tests. */
    public Policy(
            String environment,
            Map<RiskFactor, Integer> weights,
            Thresholds thresholds,
            Map<String, Double> environments,
            Map<ActionType, ActionPolicy> actions) {
        this(environment, weights, thresholds, environments, actions, AllowList.NONE);
    }

    public int weight(RiskFactor factor) {
        return weights.get(factor);
    }

    /** Risk of {@code name}, or {@link #UNKNOWN_ENVIRONMENT_RISK} if the policy doesn't list it. */
    public double environmentRisk(String name) {
        return name == null ? UNKNOWN_ENVIRONMENT_RISK : environments.getOrDefault(name.strip(), UNKNOWN_ENVIRONMENT_RISK);
    }

    /** The action's risk values, or null when the policy doesn't list the action. */
    public ActionPolicy action(ActionType type) {
        return actions.get(type);
    }

    static void requireUnit(Double value, String field) {
        if (value == null || value.isNaN() || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0.0 and 1.0, was " + value);
        }
    }
}
