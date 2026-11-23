package edu.neu.strata.healer.execution;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

/** Runs one healing action against the Docker Engine (implemented by DockerActionExecutor). */
public interface ActionExecutor {

    ExecutionResult execute(ActionType type, String target);

    /**
     * Puts back what a successful {@link #execute} changed, when that is possible (for example the extra
     * instance of a scale out). Many actions can't be undone, so by default nothing is done and the
     * result is a failure saying so.
     */
    default ExecutionResult undo(ExecutionResult executed) {
        return ExecutionResult.failed(executed.action(), executed.target(),
                "nothing to undo for " + executed.action());
    }
}
