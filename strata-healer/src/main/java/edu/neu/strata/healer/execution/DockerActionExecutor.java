package edu.neu.strata.healer.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

@Component
public class DockerActionExecutor implements ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerActionExecutor.class);

    private final DockerClient dockerClient;
    private final ScaleOutAction scaleOut;
    private final RollbackAction rollback;

    public DockerActionExecutor(DockerClient dockerClient, ScaleOutAction scaleOut, RollbackAction rollback) {
        this.dockerClient = dockerClient;
        this.scaleOut = scaleOut;
        this.rollback = rollback;
    }

    @Override
    public ExecutionResult execute(ActionType type, String target) {
        try {
            ExecutionResult result = switch (type) {
                case RESTART -> restart(target);
                case SCALE_OUT -> scaleOut.run(target);
                case ROLLBACK -> rollback.run(target);
            };
            log.info("{} on {}: {} {}", type, target, result.success() ? "ok" : "failed", result.detail());
            return result;
        } catch (Exception e) {
            log.warn("Could not run {} on {}: {}", type, target, e.getMessage());
            return ExecutionResult.failed(type, target, e.getMessage());
        }
    }

    @Override
    public ExecutionResult undo(ExecutionResult executed) {
        if (executed.action() != ActionType.SCALE_OUT) {
            return ActionExecutor.super.undo(executed);
        }
        try {
            ExecutionResult result = scaleOut.undo(executed);
            log.info("Undo of {} on {}: {} {}", executed.action(), executed.target(),
                    result.success() ? "ok" : "failed", result.detail());
            return result;
        } catch (Exception e) {
            log.warn("Could not undo {} on {}: {}", executed.action(), executed.target(), e.getMessage());
            return ExecutionResult.failed(executed.action(), executed.target(), e.getMessage());
        }
    }

    private ExecutionResult restart(String target) {
        dockerClient.restartContainerCmd(target).exec();
        return ExecutionResult.succeeded(ActionType.RESTART, target);
    }
}
