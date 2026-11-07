package edu.neu.strata.healer.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;

class RuleMatcherTest {

    private final RuleMatcher matcher = new RuleMatcher();

    private Alert alert(String name, String container) {
        return new Alert(name, "order-service", container, "critical", Instant.parse("2026-11-06T10:00:00Z"));
    }

    @Test
    void crashAlertProposesRestart() {
        Optional<Diagnosis> result = matcher.match(alert("ServiceDown", "strata-order-service-1"), List.of());

        assertTrue(result.isPresent());
        assertEquals("service-down-restart", result.get().ruleId());
        assertEquals(ActionType.RESTART, result.get().proposedAction());
        assertEquals("strata-order-service-1", result.get().target());
    }

    @Test
    void outOfMemoryLogGetsTheMoreSpecificRule() {
        List<String> logs = List.of("{\"message\":\"java.lang.OutOfMemoryError: Java heap space\"}");

        Diagnosis diagnosis = matcher.match(alert("ServiceDown", "strata-order-service-1"), logs).orElseThrow();

        assertEquals("out-of-memory-restart", diagnosis.ruleId());
    }

    @Test
    void unknownAlertHasNoDiagnosis() {
        assertTrue(matcher.match(alert("SomethingElse", "strata-order-service-1"), List.of()).isEmpty());
    }

    @Test
    void usesServiceNameWhenAlertHasNoContainer() {
        Diagnosis diagnosis = matcher.match(alert("ServiceDown", null), List.of()).orElseThrow();

        assertEquals("order-service", diagnosis.target());
    }

    @Test
    void firstMatchingRuleWins() {
        RuleMatcher custom = new RuleMatcher(List.of(
                new Rule("first", "ServiceDown", null, ActionType.RESTART, 0.5),
                new Rule("second", "ServiceDown", null, ActionType.ROLLBACK, 0.9)));

        assertEquals("first", custom.match(alert("ServiceDown", "c1"), List.of()).orElseThrow().ruleId());
    }
}
