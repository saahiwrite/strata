package edu.neu.strata.healer.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a decision's {@code risk_factors} JSON (e.g. {@code {"blastRadius":25}}) for display. */
final class RiskFactors {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Integer>> TYPE = new TypeReference<>() {
    };

    private RiskFactors() {
    }

    /** The factors, largest first; empty when there are none or the JSON can't be read. */
    static Map<String, Integer> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> parsed = JSON.readValue(json, TYPE);
            Map<String, Integer> sorted = new LinkedHashMap<>();
            parsed.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> sorted.put(e.getKey(), e.getValue()));
            return Collections.unmodifiableMap(sorted);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
