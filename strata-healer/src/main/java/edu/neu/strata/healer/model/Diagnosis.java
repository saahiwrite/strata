package edu.neu.strata.healer.model;

import java.util.Objects;

/**
 * What the diagnosis stage concluded and which action it proposes.
 *
 * @param ruleId         the rule that matched (from rules.yaml), or the LLM's id for an LLM diagnosis
 * @param proposedAction the action to take
 * @param target         the container or service the action applies to
 * @param confidence     how sure the diagnosis is, from 0.0 to 1.0 (a risk factor)
 */
public record Diagnosis(String ruleId, ActionType proposedAction, String target, double confidence) {

    public Diagnosis {
        ruleId = Alert.requireText(ruleId, "ruleId");
        Objects.requireNonNull(proposedAction, "proposedAction");
        target = Alert.requireText(target, "target");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0, was " + confidence);
        }
    }
}
