package edu.neu.strata.healer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DiagnosisTest {

    @Test
    void acceptsConfidenceAtBothEnds() {
        assertDoesNotThrow(() -> new Diagnosis("crash", ActionType.RESTART, "order-service", 0.0));
        Diagnosis sure = new Diagnosis("crash", ActionType.RESTART, "order-service", 1.0);

        assertEquals(ActionType.RESTART, sure.proposedAction());
        assertEquals(1.0, sure.confidence());
    }

    @Test
    void rejectsConfidenceOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis("crash", ActionType.RESTART, "order-service", -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis("crash", ActionType.RESTART, "order-service", 1.01));
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis("crash", ActionType.RESTART, "order-service", Double.NaN));
    }

    @Test
    void rejectsMissingRuleActionOrTarget() {
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis("", ActionType.RESTART, "order-service", 0.9));
        assertThrows(NullPointerException.class, () -> new Diagnosis("crash", null, "order-service", 0.9));
        assertThrows(IllegalArgumentException.class, () -> new Diagnosis("crash", ActionType.RESTART, " ", 0.9));
    }
}
