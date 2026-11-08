package edu.neu.strata.healer.model;

/**
 * The healing actions STRATA knows about. Only actions listed in the policy's
 * allow-list may ever run; anything else proposed (for example by the LLM) is rejected.
 */
public enum ActionType {
    /** Restart one container. Phase 2. */
    RESTART,
    /** Start one more instance of a service. Phase 3. */
    SCALE_OUT,
    /** Go back to the previous image of a service. Phase 3. */
    ROLLBACK
}
