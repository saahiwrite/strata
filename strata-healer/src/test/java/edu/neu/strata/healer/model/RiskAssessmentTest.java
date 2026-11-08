package edu.neu.strata.healer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskAssessmentTest {

    @Test
    void acceptsScoresFromZeroToHundred() {
        assertEquals(0, new RiskAssessment(0, AutonomyLevel.L0_AUTO, Map.of()).score());
        assertEquals(100, new RiskAssessment(100, AutonomyLevel.L3_HUMAN_ONLY, Map.of()).score());
    }

    @Test
    void rejectsScoresOutOfRangeAndMissingLevel() {
        assertThrows(IllegalArgumentException.class, () -> new RiskAssessment(-1, AutonomyLevel.L0_AUTO, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RiskAssessment(101, AutonomyLevel.L3_HUMAN_ONLY, Map.of()));
        assertThrows(NullPointerException.class, () -> new RiskAssessment(10, null, Map.of()));
    }

    @Test
    void factorsAreCopiedAndCannotBeChangedLater() {
        Map<String, Integer> factors = new HashMap<>();
        factors.put("blastRadius", 10);
        RiskAssessment assessment = new RiskAssessment(10, AutonomyLevel.L0_AUTO, factors);

        factors.put("reversibility", 50);

        assertEquals(Map.of("blastRadius", 10), assessment.factors());
        assertThrows(UnsupportedOperationException.class, () -> assessment.factors().put("x", 1));
    }

    @Test
    void missingFactorsBecomeAnEmptyMap() {
        assertTrue(new RiskAssessment(5, AutonomyLevel.L0_AUTO, null).factors().isEmpty());
    }
}
