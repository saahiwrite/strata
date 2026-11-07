package edu.neu.strata.healer.execution;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

/** Runs one healing action against the Docker Engine (implemented by DockerActionExecutor). */
public interface ActionExecutor {

    ExecutionResult execute(ActionType type, String target);
}
