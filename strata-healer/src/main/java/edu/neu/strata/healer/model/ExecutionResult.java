package edu.neu.strata.healer.model;

import java.util.Objects;

/**
 * The outcome of running one action through an {@code ActionExecutor}.
 *
 * @param action  the action that was run
 * @param target  the container or service it ran on
 * @param success whether the Docker call itself succeeded (recovery is checked separately)
 * @param detail  a short human-readable note, e.g. an error message
 */
public record ExecutionResult(ActionType action, String target, boolean success, String detail) {

    public ExecutionResult {
        Objects.requireNonNull(action, "action");
        target = Alert.requireText(target, "target");
        detail = detail == null ? "" : detail;
    }

    public static ExecutionResult succeeded(ActionType action, String target) {
        return new ExecutionResult(action, target, true, "");
    }

    public static ExecutionResult failed(ActionType action, String target, String detail) {
        return new ExecutionResult(action, target, false, detail);
    }
}
