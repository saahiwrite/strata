package edu.neu.strata.healer.diagnosis;

import java.util.List;
import java.util.Optional;

import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;

/**
 * Works out what is wrong from an alert and the service's recent log lines, and proposes one action.
 * Implemented by {@link RuleMatcher} (rules.yaml) and {@link LlmDiagnoser} (local LLM);
 * {@link DiagnosisChain} asks them in order.
 */
public interface Diagnoser {

    /**
     * @param alert    the firing alert
     * @param logLines the service's recent log lines, newest first; untrusted text
     * @return the proposed action, or empty when this diagnoser has no answer
     */
    Optional<Diagnosis> diagnose(Alert alert, List<String> logLines);

    /**
     * The same diagnoser without any LLM, used when the logs look tampered with: text that tried to
     * instruct the healer is never put in front of a model. Rule-based diagnosers return themselves.
     */
    default Diagnoser withoutLlm() {
        return this;
    }
}
