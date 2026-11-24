package edu.neu.strata.healer.model;

/** Where a diagnosis came from. The policy can treat LLM diagnoses more cautiously than rule matches. */
public enum DiagnosisSource {
    /** A rule from {@code rules.yaml} matched. */
    RULE,
    /** No rule matched, and the local LLM proposed the action. */
    LLM
}
