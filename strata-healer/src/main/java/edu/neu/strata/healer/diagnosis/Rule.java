package edu.neu.strata.healer.diagnosis;

import java.util.List;

import edu.neu.strata.healer.model.ActionType;

public record Rule(String id, String alertName, List<String> logContains, ActionType action, double confidence) {

    public Rule {
        logContains = logContains == null ? List.of() : logContains;
    }
}
