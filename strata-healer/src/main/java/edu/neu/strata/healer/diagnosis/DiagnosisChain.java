package edu.neu.strata.healer.diagnosis;

import java.util.List;
import java.util.Optional;

import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;

/**
 * Asks each diagnoser in order and returns the first answer (chain of responsibility).
 * STRATA's chain is the rules first, then the LLM, so the LLM only runs when no rule matches (FR3).
 */
public class DiagnosisChain implements Diagnoser {

    private final List<Diagnoser> diagnosers;

    public DiagnosisChain(List<Diagnoser> diagnosers) {
        if (diagnosers == null || diagnosers.isEmpty()) {
            throw new IllegalArgumentException("a diagnosis chain needs at least one diagnoser");
        }
        this.diagnosers = List.copyOf(diagnosers);
    }

    @Override
    public Optional<Diagnosis> diagnose(Alert alert, List<String> logLines) {
        for (Diagnoser diagnoser : diagnosers) {
            Optional<Diagnosis> found = diagnoser.diagnose(alert, logLines);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public Diagnoser withoutLlm() {
        return new DiagnosisChain(diagnosers.stream().map(Diagnoser::withoutLlm).toList());
    }

    /** The diagnosers in the order they are asked. */
    public List<Diagnoser> diagnosers() {
        return diagnosers;
    }
}
