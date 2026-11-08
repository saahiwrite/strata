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

    public DockerActionExecutor(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public ExecutionResult execute(ActionType type, String target) {
        if (type != ActionType.RESTART) {
            return ExecutionResult.failed(type, target, type + " is not supported yet");
        }

        try {
            dockerClient.restartContainerCmd(target).exec();
            log.info("Restarted container {}", target);
            return ExecutionResult.succeeded(type, target);
        } catch (Exception e) {
            log.warn("Could not restart container {}: {}", target, e.getMessage());
            return ExecutionResult.failed(type, target, e.getMessage());
        }
    }
}
