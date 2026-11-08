package edu.neu.strata.healer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionResultTest {

    @Test
    void factoriesSetSuccessAndDetail() {
        ExecutionResult ok = ExecutionResult.succeeded(ActionType.RESTART, "order-service");
        ExecutionResult bad = ExecutionResult.failed(ActionType.RESTART, "order-service", "container not found");

        assertTrue(ok.success());
        assertEquals("", ok.detail());
        assertFalse(bad.success());
        assertEquals("container not found", bad.detail());
    }

    @Test
    void rejectsMissingActionOrTarget() {
        assertThrows(NullPointerException.class, () -> ExecutionResult.succeeded(null, "order-service"));
        assertThrows(IllegalArgumentException.class, () -> ExecutionResult.succeeded(ActionType.RESTART, ""));
    }
}
