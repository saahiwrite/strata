package edu.neu.strata.healer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutonomyLevelTest {

    @Test
    void onlyL0AndL1RunAutomatically() {
        assertTrue(AutonomyLevel.L0_AUTO.runsAutomatically());
        assertTrue(AutonomyLevel.L1_AUTO_UNDO.runsAutomatically());
        assertFalse(AutonomyLevel.L2_APPROVAL.runsAutomatically());
        assertFalse(AutonomyLevel.L3_HUMAN_ONLY.runsAutomatically());
    }

    @Test
    void levelsAreOrderedFromLeastToMostCautious() {
        AutonomyLevel[] levels = AutonomyLevel.values();
        for (int i = 1; i < levels.length; i++) {
            assertTrue(levels[i - 1].compareTo(levels[i]) < 0);
        }
    }

    @Test
    void stricterOfPicksTheMoreCautiousLevel() {
        assertEquals(AutonomyLevel.L2_APPROVAL, AutonomyLevel.L0_AUTO.stricterOf(AutonomyLevel.L2_APPROVAL));
        assertEquals(AutonomyLevel.L3_HUMAN_ONLY, AutonomyLevel.L3_HUMAN_ONLY.stricterOf(AutonomyLevel.L1_AUTO_UNDO));
        assertEquals(AutonomyLevel.L1_AUTO_UNDO, AutonomyLevel.L1_AUTO_UNDO.stricterOf(AutonomyLevel.L1_AUTO_UNDO));
        assertEquals(AutonomyLevel.L0_AUTO, AutonomyLevel.L0_AUTO.stricterOf(null));
    }
}
